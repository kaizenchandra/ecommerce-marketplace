package com.example.marketplace.events;

import com.example.marketplace.shared.Rules;
import com.example.marketplace.shared.Store;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class EventService {
    private final Store s;
    private final TransactionTemplate tx;
    private final ObjectProvider<KafkaTemplate<String, String>> kafka;
    private final boolean external;
    private final String topic;

    public EventService(
            Store s,
            PlatformTransactionManager manager,
            ObjectProvider<KafkaTemplate<String, String>> kafka,
            @Value("${marketplace.events.transport:local}") String transport,
            @Value("${marketplace.events.topic:marketplace.events.v1}") String topic) {
        this.s = s;
        this.tx = new TransactionTemplate(manager);
        this.kafka = kafka;
        this.external = transport.equals("kafka");
        this.topic = topic;
    }

    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelay = 1000,
            initialDelayString = "${marketplace.worker-initial-delay:1000}",
            scheduler = "eventScheduler")
    public void relay() {
        var events =
                tx.execute(
                        t ->
                                s.db.queryForList(
                                        "update outbox_events set attempts=attempts+1,next_attempt=now()+interval '30"
                                                + " seconds' where id in (select id from outbox_events where published_at"
                                                + " is null and delivery_state='READY' and attempts<8 and"
                                                + " next_attempt<=now() order by created_at limit 16 for update skip"
                                                + " locked) returning *"));
        for (var e : events) {
            try {
                var payload =
                        s.json.readValue(
                                e.get("payload").toString(),
                                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                                });
                var envelope =
                        new Envelope(
                                (UUID) e.get("id"),
                                ((Number) e.get("schema_version")).intValue(),
                                (UUID) e.get("aggregate_id"),
                                ((Number) e.get("aggregate_version")).longValue(),
                                (UUID) e.get("correlation_id"),
                                e.get("event_type").toString(),
                                ((java.sql.Timestamp) e.get("created_at")).toInstant(),
                                payload);
                if (external)
                    kafka
                            .getObject()
                            .send(topic, envelope.aggregateId().toString(), s.json(envelope))
                            .get(6, TimeUnit.SECONDS);
                else consume(envelope);
                // Crash after broker acknowledgement and before this update intentionally causes duplicate
                // publication.
                s.db.update(
                        "update outbox_events set published_at=now(),delivery_state='DONE' where id=?",
                        e.get("id"));
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }
        s.db.update(
                "update outbox_events set delivery_state='MANUAL_REVIEW' where attempts>=8 and"
                        + " delivery_state='READY' and published_at is null and next_attempt<=now()");
    }

    public void consume(Envelope event) {
        Rules.require(
                event != null
                        && event.eventId() != null
                        && event.aggregateId() != null
                        && event.correlationId() != null
                        && event.schemaVersion() == 1
                        && event.aggregateVersion() >= 0
                        && event.eventType() != null
                        && event.eventType().length() <= 100,
                "Unsupported event envelope");
        tx.executeWithoutResult(
                t -> {
                    // Do not trust the transport as an authority for order/payment transitions.
                    s.db.queryForMap("select * from orders where id=?", event.aggregateId());
                    if (s.db.update(
                            "insert into processed_events(event_id,consumer) values (?,'notifications-v1') on"
                                    + " conflict do nothing",
                            event.eventId())
                            == 0) return;
                    s.db.update(
                            "insert into notifications(event_id,customer_id,message) select ?,customer_id,? from"
                                    + " orders where id=?",
                            event.eventId(),
                            event.eventType(),
                            event.aggregateId());
                    s.db.update(
                            "insert into consumer_versions(consumer,aggregate_id,version) values"
                                    + " ('notifications-v1',?,?) on conflict(consumer,aggregate_id) do update set"
                                    + " version=greatest(consumer_versions.version,excluded.version)",
                            event.aggregateId(),
                            event.aggregateVersion());
                });
    }

    public void replay(String payload, String key) throws Exception {
        Rules.require(external, "Kafka transport required");
        kafka.getObject().send(topic, key, payload).get(6, TimeUnit.SECONDS);
    }

    public record Envelope(
            UUID eventId,
            int schemaVersion,
            UUID aggregateId,
            long aggregateVersion,
            UUID correlationId,
            String eventType,
            Instant occurredAt,
            Map<String, Object> payload) {
    }
}
