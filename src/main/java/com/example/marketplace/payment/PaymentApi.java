package com.example.marketplace.payment;

import com.example.marketplace.checkout.Workflow;
import com.example.marketplace.shared.Rules;
import com.example.marketplace.shared.Store;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentApi {
    private final Store s;
    private final Workflow workflow;
    private final PaymentProvider provider;
    private final String secret;
    private final String providerName;

    public PaymentApi(
            Store s,
            Workflow workflow,
            PaymentProvider provider,
            @Value("${marketplace.webhook-secret:disabled}") String secret,
            @Value("${marketplace.payment.provider:local}") String providerName) {
        this.s = s;
        this.workflow = workflow;
        this.provider = provider;
        this.secret = secret;
        this.providerName = providerName;
    }

    @PostMapping("/{id}/confirm")
    Object confirm(@PathVariable UUID id) {
        UUID order =
                s.db.queryForObject(
                        "select p.order_id from payments p join orders o on o.id=p.order_id where p.id=? and"
                                + " o.customer_id=?",
                        UUID.class,
                        id,
                        s.user());
        workflow.advance(order);
        return s.db.queryForMap("select * from payments where id=?", id);
    }

    @PostMapping("/webhooks/local")
    @Transactional
    Object webhook(
            @RequestHeader("X-Timestamp") long timestamp,
            @RequestHeader("X-Signature") String signature,
            @RequestBody String body)
            throws Exception {
        if (!"local".equals(providerName)
                || body.length() > 4096
                || Math.abs(java.time.Instant.now().getEpochSecond() - timestamp) > 300)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expected = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
        byte[] received;
        try {
            received = HexFormat.of().parseHex(signature);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid signature");
        }
        if (!MessageDigest.isEqual(expected, received))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid signature");
        Webhook event = s.json.readValue(body, Webhook.class);
        Rules.require(
                event.eventId() != null
                        && event.eventId().length() <= 100
                        && Set.of("CAPTURED", "FAILED", "REFUNDED").contains(event.status()),
                "Invalid event");
        UUID order =
                s.db.queryForObject(
                        "select order_id from payments where id=?", UUID.class, event.paymentId());
        s.db.queryForMap("select * from orders where id=? for update", order);
        if (s.db.update(
                "insert into payment_webhook_events(event_id,payment_id,status) values (?,?,?) on"
                        + " conflict do nothing",
                event.eventId(),
                event.paymentId(),
                event.status())
                == 0) return Map.of("duplicate", true);
        // Hints never regress state. Reconcile authoritative provider state, including contradictory
        // events.
        workflow.complete(order, provider.lookup(order.toString()));
        return Map.of("accepted", true);
    }

    public record Webhook(String eventId, UUID paymentId, String status) {
    }
}
