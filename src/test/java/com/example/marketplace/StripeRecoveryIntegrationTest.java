package com.example.marketplace;

import com.example.marketplace.checkout.Workflow;
import com.example.marketplace.payment.PaymentProvider;
import com.example.marketplace.payment.StripeWebhookApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("integration")
@Testcontainers
@ActiveProfiles("local")
@AutoConfigureMockMvc
@SpringBootTest(
        properties = {
                "LOCAL_PASSWORD=test-password-only",
                "marketplace.payment.provider=stripe",
                "marketplace.stripe.secret-key=sk_test_contract_only",
                "marketplace.stripe.webhook-secret=whsec_contract",
                "marketplace.stripe.timeout-ms=300",
                "marketplace.public-url=http://localhost:8097",
                "marketplace.worker-initial-delay=3600000"
        })
class StripeRecoveryIntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Map<String, Map<String, Object>> remote = new ConcurrentHashMap<>();
    static final Map<String, Map<String, Object>> refunds = new ConcurrentHashMap<>();
    static final AtomicBoolean loseCreate = new AtomicBoolean(), loseRefund = new AtomicBoolean();
    static final AtomicBoolean refundPending = new AtomicBoolean(), failGet = new AtomicBoolean();
    static final AtomicInteger createCalls = new AtomicInteger(),
            refundCalls = new AtomicInteger(),
            getCalls = new AtomicInteger();
    static final HttpServer stripe = start();
    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");
    @Autowired
    JdbcTemplate db;
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    Workflow workflow;
    @Autowired
    PaymentProvider provider;

    static HttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/", StripeRecoveryIntegrationTest::handle);
            server.start();
            return server;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static void handle(HttpExchange x) throws IOException {
        try {
            assertEquals("Bearer sk_test_contract_only", x.getRequestHeaders().getFirst("Authorization"));
            String path = x.getRequestURI().getPath();
            Map<String, Object> result = null;
            if (x.getRequestMethod().equals("POST") && path.equals("/v1/checkout/sessions")) {
                createCalls.incrementAndGet();
                Map<String, String> f =
                        form(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String key = x.getRequestHeaders().getFirst("Idempotency-Key");
                result =
                        remote.computeIfAbsent(
                                key,
                                k -> {
                                    var m = new ConcurrentHashMap<String, Object>();
                                    m.put("id", "cs_test_" + UUID.randomUUID().toString().replace("-", ""));
                                    m.put("livemode", false);
                                    m.put("currency", "inr");
                                    m.put(
                                            "amount_total",
                                            Long.parseLong(f.get("line_items[0][price_data][unit_amount]")));
                                    m.put("client_reference_id", f.get("client_reference_id"));
                                    m.put("status", "open");
                                    m.put("payment_status", "unpaid");
                                    m.put("payment_intent", "pi_test_intent");
                                    m.put("url", "https://checkout.stripe.com/c/pay/cs_test_contract");
                                    return m;
                                });
                if (loseCreate.getAndSet(false)) Thread.sleep(1000);
            } else if (path.startsWith("/v1/checkout/sessions/")) {
                getCalls.incrementAndGet();
                if (x.getRequestMethod().equals("GET") && failGet.getAndSet(false)) {
                    x.sendResponseHeaders(503, -1);
                    return;
                }
                String id = path.split("/")[4];
                result =
                        remote.values().stream().filter(m -> m.get("id").equals(id)).findFirst().orElseThrow();
                if (path.endsWith("/expire") && !result.get("payment_status").equals("paid"))
                    result.put("status", "expired");
            } else if (x.getRequestMethod().equals("POST") && path.equals("/v1/refunds")) {
                refundCalls.incrementAndGet();
                var f = form(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String key = x.getRequestHeaders().getFirst("Idempotency-Key");
                result =
                        refunds.computeIfAbsent(
                                key,
                                k -> {
                                    var m = new ConcurrentHashMap<String, Object>();
                                    m.put("id", "re_test_" + UUID.randomUUID().toString().replace("-", ""));
                                    m.put("livemode", false);
                                    m.put("payment_intent", f.get("payment_intent"));
                                    m.put("amount", Long.parseLong(f.get("amount")));
                                    m.put("status", refundPending.get() ? "pending" : "succeeded");
                                    return m;
                                });
                if (loseRefund.getAndSet(false)) Thread.sleep(1000);
            } else if (path.startsWith("/v1/refunds/")) {
                String id = path.substring("/v1/refunds/".length());
                result =
                        refunds.values().stream().filter(m -> m.get("id").equals(id)).findFirst().orElseThrow();
            }
            if (result == null) {
                x.sendResponseHeaders(404, -1);
                return;
            }
            byte[] body = JSON.writeValueAsBytes(result);
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(200, body.length);
            x.getResponseBody().write(body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            x.close();
        }
    }

    static Map<String, String> form(String body) {
        Map<String, String> m = new HashMap<>();
        for (String pair : body.split("&")) {
            String[] p = pair.split("=", 2);
            m.put(
                    URLDecoder.decode(p[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(p[1], StandardCharsets.UTF_8));
        }
        return m;
    }

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
        r.add("marketplace.stripe.api-base", () -> "http://127.0.0.1:" + stripe.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        stripe.stop(0);
    }

    @BeforeEach
    void reset() {
        db.execute("truncate customers cascade");
        db.execute("truncate outbox_events,processed_events,consumer_versions,event_failures");
        db.execute("truncate stripe_operations");
        db.update("update inventory set reserved=0,on_hand=100");
        remote.clear();
        refunds.clear();
        loseCreate.set(false);
        loseRefund.set(false);
        refundPending.set(false);
        failGet.set(false);
        createCalls.set(0);
        refundCalls.set(0);
        getCalls.set(0);
    }

    UUID order() throws Exception {
        db.update("insert into customers(id,display_name) values ('alice','Alice')");
        UUID a = UUID.randomUUID();
        db.update("insert into addresses values (?,'alice','1 Test St','Pune','411001','IN')", a);
        db.update("insert into carts(customer_id) values ('alice')");
        db.update(
                "insert into cart_items values (?,'alice','40000000-0000-0000-0000-000000000001',1)",
                UUID.randomUUID());
        var q = send("/checkout/quotes", Map.of("addressId", a));
        var req =
                post("/api/v1/checkouts")
                        .with(user("alice"))
                        .with(csrf())
                        .header("Idempotency-Key", "test")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("quoteId", q.get("id").asText())));
        return UUID.fromString(
                json.readTree(
                                mvc.perform(req)
                                        .andExpect(status().isOk())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .get("id")
                        .asText());
    }

    JsonNode send(String path, Object body) throws Exception {
        return json.readTree(
                mvc.perform(
                                post("/api/v1" + path)
                                        .with(user("alice"))
                                        .with(csrf())
                                        .contentType("application/json")
                                        .content(json.writeValueAsString(body)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    void due(UUID order) {
        db.update(
                "update checkout_workflows set next_attempt=now()-interval '1 second' where order_id=?",
                order);
    }

    void paid() {
        remote
                .values()
                .forEach(
                        m -> {
                            m.put("payment_status", "paid");
                            m.put("status", "complete");
                        });
    }

    @Test
    void timeoutAfterRemoteCreateRecoversSameSession() throws Exception {
        UUID id = order();
        loseCreate.set(true);
        assertThrows(Exception.class, () -> workflow.advance(id));
        assertEquals(1, remote.size());
        assertNull(
                db.queryForObject(
                        "select provider_id from stripe_operations where kind='CHECKOUT'", String.class));
        due(id);
        workflow.advance(id);
        assertEquals(1, remote.size());
        assertEquals(2, createCalls.get());
        assertEquals(
                "PAYMENT_PENDING",
                db.queryForObject("select status from orders where id=?", String.class, id));
        assertTrue(
                db.queryForObject("select checkout_url from payments where order_id=?", String.class, id)
                        .startsWith("https://checkout.stripe.com/"));
        paid();
        due(id);
        workflow.advance(id);
        assertEquals(
                "CONFIRMED", db.queryForObject("select status from orders where id=?", String.class, id));
        assertEquals(1, remote.size());
    }

    @Test
    void cancelledUnpaidCheckoutExpiresRemotely() throws Exception {
        UUID id = order();
        workflow.advance(id);
        send("/orders/" + id + "/cancel", Map.of());
        due(id);
        workflow.advance(id);
        assertEquals("expired", remote.values().iterator().next().get("status"));
        assertEquals(
                0,
                db.queryForObject(
                        "select reserved from inventory where offer_id='40000000-0000-0000-0000-000000000001'",
                        Integer.class));
    }

    @Test
    void pendingRefundIsRetrievedUntilSucceededWithoutNewPost() throws Exception {
        UUID id = order();
        workflow.advance(id);
        paid();
        due(id);
        workflow.advance(id);
        send("/orders/" + id + "/cancel", Map.of());
        refundPending.set(true);
        UUID refund = db.queryForObject("select id from refunds", UUID.class);
        assertThrows(Exception.class, () -> workflow.refund(refund));
        assertEquals(
                "PENDING",
                db.queryForObject("select status from refunds where id=?", String.class, refund));
        refunds.values().forEach(m -> m.put("status", "succeeded"));
        db.update("update refunds set next_attempt=now()-interval '1 second' where id=?", refund);
        workflow.refund(refund);
        assertEquals(1, refundCalls.get());
        assertEquals(
                "REFUNDED",
                db.queryForObject("select status from payments where order_id=?", String.class, id));
    }

    @Test
    void lostRefundResponseRecoversSameFinancialOperation() throws Exception {
        UUID id = order();
        workflow.advance(id);
        paid();
        due(id);
        workflow.advance(id);
        send("/orders/" + id + "/cancel", Map.of());
        loseRefund.set(true);
        UUID refund = db.queryForObject("select id from refunds", UUID.class);
        assertThrows(Exception.class, () -> workflow.refund(refund));
        assertEquals(1, refunds.size());
        db.update("update refunds set next_attempt=now()-interval '1 second' where id=?", refund);
        workflow.refund(refund);
        assertEquals(1, refunds.size());
        assertEquals(
                "COMPLETED",
                db.queryForObject("select status from refunds where id=?", String.class, refund));
    }

    @Test
    void staleAmbiguousIdempotencyNeverReposts() throws Exception {
        UUID id = order();
        loseCreate.set(true);
        assertThrows(Exception.class, () -> workflow.advance(id));
        db.update("update stripe_operations set created_at=now()-interval '25 hours'");
        due(id);
        assertThrows(Exception.class, () -> workflow.advance(id));
        assertEquals(1, createCalls.get());
    }

    @Test
    void durableWebhookDuplicateAndWorkerRecovery() throws Exception {
        UUID id = order();
        workflow.advance(id);
        paid();
        Map<String, Object> event =
                Map.of(
                        "id",
                        "evt_contract_1",
                        "livemode",
                        false,
                        "type",
                        "checkout.session.completed",
                        "data",
                        Map.of("object", remote.values().iterator().next()));
        byte[] body = json.writeValueAsBytes(event);
        long now = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("whsec_contract".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((now + ".").getBytes(StandardCharsets.UTF_8));
        String signature = "t=" + now + ",v1=" + HexFormat.of().formatHex(mac.doFinal(body));
        int calls = getCalls.get();
        for (int i = 0; i < 2; i++)
            mvc.perform(
                            post("/api/v1/payments/webhooks/stripe")
                                    .header("Stripe-Signature", signature)
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isOk());
        assertEquals(calls, getCalls.get(), "Webhook must enqueue without provider network IO");
        assertEquals(
                1, db.queryForObject("select count(*) from provider_webhook_inbox", Integer.class));
        failGet.set(true);
        workflow.tick();
        assertEquals(
                "READY", db.queryForObject("select status from provider_webhook_inbox", String.class));
        db.update("update provider_webhook_inbox set next_attempt=now()-interval '1 second'");
        workflow.tick();
        assertEquals(
                "CONFIRMED", db.queryForObject("select status from orders where id=?", String.class, id));
        assertEquals(
                "DONE", db.queryForObject("select status from provider_webhook_inbox", String.class));
    }

    @Test
    void databaseFailureAfterProviderSuccessRollsBackAndResumes() throws Exception {
        UUID id = order();
        workflow.advance(id);
        paid();
        due(id);
        db.execute(
                "create function reject_confirmation() returns trigger language plpgsql as $$ begin if"
                        + " NEW.status='CONFIRMED' then raise exception 'injected confirmation failure'; end"
                        + " if; return NEW; end $$");
        db.execute(
                "create trigger reject_confirmation before update on orders for each row execute function"
                        + " reject_confirmation()");
        try {
            assertThrows(Exception.class, () -> workflow.advance(id));
            assertEquals(
                    "CREATED",
                    db.queryForObject("select status from payments where order_id=?", String.class, id));
            assertEquals(
                    1,
                    db.queryForObject(
                            "select reserved from inventory where"
                                    + " offer_id='40000000-0000-0000-0000-000000000001'",
                            Integer.class));
        } finally {
            db.execute("drop trigger reject_confirmation on orders");
            db.execute("drop function reject_confirmation()");
        }
        due(id);
        workflow.advance(id);
        assertEquals(
                "CONFIRMED", db.queryForObject("select status from orders where id=?", String.class, id));
        assertEquals(1, createCalls.get());
    }

    @Test
    void stripeSignatureRejectsTamperAndOldTimestamp() throws Exception {
        long now = Instant.now().getEpochSecond();
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        assertThrows(
                Exception.class,
                () -> StripeWebhookApi.verify("t=" + (now - 301) + ",v1=00", body, "whsec_contract", now));
        assertThrows(
                Exception.class,
                () -> StripeWebhookApi.verify("t=" + now + ",v1=00", body, "whsec_contract", now));
    }
}
