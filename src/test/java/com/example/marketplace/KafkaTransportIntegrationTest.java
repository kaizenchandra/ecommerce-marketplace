package com.example.marketplace;

import com.example.marketplace.checkout.Workflow;
import com.example.marketplace.events.EventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("integration")
@Testcontainers
@ActiveProfiles({"local", "kafka"})
@AutoConfigureMockMvc
@SpringBootTest(
        properties = {
                "LOCAL_PASSWORD=test-only",
                "marketplace.webhook-secret=test-only",
                "marketplace.worker-initial-delay=3600000"
        })
class KafkaTransportIntegrationTest {
    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container
    static KafkaContainer broker = new KafkaContainer("apache/kafka-native:3.9.1");
    @Autowired
    JdbcTemplate db;
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    EventService events;
    @Autowired
    Workflow workflow;
    @Autowired
    KafkaTemplate<String, String> kafka;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
        r.add("spring.kafka.bootstrap-servers", broker::getBootstrapServers);
    }

    @BeforeEach
    void reset() {
        db.execute("truncate customers cascade");
        db.execute("truncate outbox_events,processed_events,consumer_versions,event_failures");
        db.execute("truncate event_failures,consumer_versions");
        db.update("update inventory set on_hand=100,reserved=0");
    }

    UUID order() throws Exception {
        db.update("insert into customers(id,display_name) values ('events','Events')");
        UUID address = UUID.randomUUID();
        db.update("insert into addresses values (?,'events','1 Main','Pune','411001','IN')", address);
        db.update("insert into carts(customer_id) values ('events')");
        db.update(
                "insert into cart_items values (?,'events','40000000-0000-0000-0000-000000000001',1)",
                UUID.randomUUID());
        var quote =
                mvc.perform(
                                post("/api/v1/checkout/quotes")
                                        .with(user("events"))
                                        .with(csrf())
                                        .contentType("application/json")
                                        .content(json.writeValueAsString(Map.of("addressId", address))))
                        .andExpect(status().isOk())
                        .andReturn();
        String q = json.readTree(quote.getResponse().getContentAsString()).get("id").asText();
        var order =
                mvc.perform(
                                post("/api/v1/checkouts")
                                        .with(user("events"))
                                        .with(csrf())
                                        .header("Idempotency-Key", "event-order")
                                        .contentType("application/json")
                                        .content(json.writeValueAsString(Map.of("quoteId", q))))
                        .andExpect(status().isOk())
                        .andReturn();
        return UUID.fromString(
                json.readTree(order.getResponse().getContentAsString()).get("id").asText());
    }

    @Test
    void realKafkaDeliveryAndPublishBeforeAckCrashDeduplicate() throws Exception {
        UUID order = order();
        workflow.advance(order);
        events.relay();
        await()
                .atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () ->
                                assertEquals(
                                        2, db.queryForObject("select count(*) from notifications", Integer.class)));
        db.update(
                "update outbox_events set published_at=null,delivery_state='READY',next_attempt=now()");
        events.relay();
        await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () ->
                                assertEquals(
                                        2, db.queryForObject("select count(*) from processed_events", Integer.class)));
        assertEquals(2, db.queryForObject("select count(*) from notifications", Integer.class));
        assertEquals(
                1L,
                db.queryForObject(
                        "select version from consumer_versions where aggregate_id=?", Long.class, order));
    }

    @Test
    void databaseConsumerFailureGoesToDltAndOriginalEventCanReplay() throws Exception {
        UUID order = order();
        db.execute(
                "create function reject_notification() returns trigger language plpgsql as $$ begin raise"
                        + " exception 'injected notification failure'; end $$");
        db.execute(
                "create trigger reject_notification before insert on notifications for each row execute"
                        + " function reject_notification()");
        try {
            events.relay();
            await()
                    .atMost(Duration.ofSeconds(25))
                    .untilAsserted(
                            () ->
                                    assertEquals(
                                            1, db.queryForObject("select count(*) from event_failures", Integer.class)));
            assertEquals(0, db.queryForObject("select count(*) from processed_events", Integer.class));
        } finally {
            db.execute("drop trigger reject_notification on notifications");
            db.execute("drop function reject_notification()");
        }
        var raw = db.queryForObject("select payload from event_failures", String.class);
        events.replay(raw, order.toString());
        await()
                .atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertEquals(
                                        1, db.queryForObject("select count(*) from notifications", Integer.class)));
    }

    @Test
    void unsupportedSchemaIsQuarantinedAndOutOfOrderVersionsNeverRegress() throws Exception {
        UUID order = order();
        UUID id = UUID.randomUUID();
        var invalid =
                new EventService.Envelope(id, 2, order, 2, order, "Unsupported", Instant.now(), Map.of());
        kafka
                .send("marketplace.events.v1", order.toString(), json.writeValueAsString(invalid))
                .get(6, TimeUnit.SECONDS);
        await()
                .atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () ->
                                assertEquals(
                                        1, db.queryForObject("select count(*) from event_failures", Integer.class)));
        events.consume(
                new EventService.Envelope(
                        UUID.randomUUID(), 1, order, 10, order, "New", Instant.now(), Map.of()));
        events.consume(
                new EventService.Envelope(
                        UUID.randomUUID(), 1, order, 3, order, "Older", Instant.now(), Map.of()));
        assertEquals(
                10L,
                db.queryForObject(
                        "select version from consumer_versions where aggregate_id=?", Long.class, order));
    }

    @Test
    void brokerOutageLeavesDurableOutboxThenResumes() throws Exception {
        order();
        var docker = broker.getDockerClient();
        docker.pauseContainerCmd(broker.getContainerId()).exec();
        try {
            events.relay();
            assertEquals(
                    0,
                    db.queryForObject(
                            "select count(*) from outbox_events where published_at is not null", Integer.class));
            assertEquals(
                    1,
                    db.queryForObject(
                            "select count(*) from outbox_events where delivery_state='READY'", Integer.class));
        } finally {
            docker.unpauseContainerCmd(broker.getContainerId()).exec();
        }
        db.update("update outbox_events set next_attempt=now()");
        events.relay();
        await()
                .atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () ->
                                assertEquals(
                                        1, db.queryForObject("select count(*) from notifications", Integer.class)));
    }
}
