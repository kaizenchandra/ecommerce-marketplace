package com.example.marketplace.payment;

import com.example.marketplace.shared.Store;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@RestController
@ConditionalOnProperty(name = "marketplace.payment.provider", havingValue = "stripe")
public class StripeWebhookApi {
    private final Store s;
    private final String secret;

    public StripeWebhookApi(Store s, @Value("${marketplace.stripe.webhook-secret}") String secret) {
        this.s = s;
        this.secret = secret;
    }

    public static void verify(String header, byte[] raw, String secret, long now) throws Exception {
        Long timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2) continue;
            try {
                if (pair[0].equals("t")) timestamp = Long.parseLong(pair[1]);
                else if (pair[0].equals("v1")) signatures.add(pair[1]);
            } catch (NumberFormatException ignored) {
            }
        }
        if (timestamp == null || timestamp < now - 300 || timestamp > now + 300)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook timestamp");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
        byte[] expected = mac.doFinal(raw);
        for (String sig : signatures)
            try {
                if (MessageDigest.isEqual(expected, HexFormat.of().parseHex(sig))) return;
            } catch (IllegalArgumentException ignored) {
            }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature");
    }

    @PostMapping("/api/v1/payments/webhooks/stripe")
    @Transactional
    public Object accept(@RequestHeader("Stripe-Signature") String signature, @RequestBody byte[] raw)
            throws Exception {
        if (raw.length > 65536) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        verify(signature, raw, secret, Instant.now().getEpochSecond());
        var event = s.json.readTree(raw);
        if (!event.has("livemode") || event.path("livemode").asBoolean())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Test events required");
        if (!Set.of(
                        "checkout.session.completed",
                        "checkout.session.expired",
                        "checkout.session.async_payment_succeeded",
                        "checkout.session.async_payment_failed")
                .contains(event.path("type").asText())) return Map.of("ignored", true);
        var object = event.path("data").path("object");
        UUID order;
        try {
            order = UUID.fromString(object.path("client_reference_id").asText());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid checkout reference");
        }
        String session = object.path("id").asText(), id = event.path("id").asText();
        if (!id.matches("evt_[A-Za-z0-9_]{1,190}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid event ID");
        // Bind signed provider event to durable intended operation; no network work while holding DB
        // locks.
        var operations =
                s.db.queryForList(
                        "select * from stripe_operations where operation_key=?", "checkout:" + order);
        if (operations.isEmpty())
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Checkout operation not yet visible; retry event");
        var op = operations.getFirst();
        if (!session.matches("cs_[A-Za-z0-9_]+")
                || (op.get("provider_id") != null && !session.equals(op.get("provider_id")))
                || !"inr".equals(object.path("currency").asText())
                || object.path("amount_total").asLong(-1) != ((Number) op.get("amount")).longValue())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Checkout event mismatch");
        // Recover a lost creation response, including after the POST idempotency retention window.
        s.db.update(
                "update stripe_operations set provider_id=? where operation_key=? and provider_id is null",
                session,
                "checkout:" + order);
        int inserted =
                s.db.update(
                        "insert into provider_webhook_inbox(provider,event_id,order_id) values ('stripe',?,?)"
                                + " on conflict do nothing",
                        id,
                        order);
        return Map.of("accepted", true, "duplicate", inserted == 0);
    }
}
