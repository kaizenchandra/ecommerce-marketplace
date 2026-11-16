package com.example.marketplace;

import com.example.marketplace.checkout.Workflow;
import com.example.marketplace.payment.PaymentProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("integration")
@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(
        properties = {
                "LOCAL_PASSWORD=test-password-only",
                "marketplace.webhook-secret=test-webhook-secret",
                "marketplace.worker-initial-delay=3600000"
        })
@AutoConfigureMockMvc
class MarketplaceIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");
    static final String OFFER = "40000000-0000-0000-0000-000000000001",
            OFFER2 = "40000000-0000-0000-0000-000000000002";
    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate db;
    @Autowired
    ObjectMapper json;
    @Autowired
    Workflow workflow;
    @Autowired
    PaymentProvider provider;
    @Autowired
    PlatformTransactionManager manager;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }

    @BeforeEach
    void reset() {
        db.execute("truncate customers cascade");
        db.execute("truncate outbox_events,processed_events,consumer_versions,event_failures");
        db.execute("truncate provider_payments cascade");
        db.update("update inventory set reserved=0,on_hand=100");
        db.update("update sellers set status='ACTIVE'");
        db.update("update products set status='ACTIVE'");
        db.update("update seller_offers set price=299900 where id=?::uuid", OFFER);
        db.update(
                "update promotions set used=0,max_uses=100,percent=10,expires_at=now()+interval '1 day'");
    }

    JsonNode send(String path, Object body, String who) throws Exception {
        return json.readTree(
                mvc.perform(
                                post("/api/v1" + path)
                                        .with(
                                                user(who)
                                                        .roles(
                                                                who.startsWith("seller")
                                                                        ? "SELLER"
                                                                        : who.equals("admin") ? "ADMIN" : "CUSTOMER"))
                                        .with(csrf())
                                        .contentType("application/json")
                                        .content(json.writeValueAsString(body)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    UUID quote(String who, List<String> offers, String coupon) throws Exception {
        mvc.perform(get("/api/v1/customers/me").with(user(who))).andExpect(status().isOk());
        String address =
                send(
                        "/customers/me/addresses",
                        Map.of("line1", "1 Main Street", "city", "Pune", "postalCode", "411001"),
                        who)
                        .get("id")
                        .asText();
        for (String offer : offers) send("/cart/items", Map.of("offerId", offer, "quantity", 1), who);
        return UUID.fromString(
                send("/checkout/quotes", Map.of("addressId", address, "coupon", coupon), who)
                        .get("id")
                        .asText());
    }

    MvcResult checkout(UUID quote, String who, String key) throws Exception {
        return mvc.perform(
                        post("/api/v1/checkouts")
                                .with(user(who))
                                .with(csrf())
                                .header("Idempotency-Key", key)
                                .contentType("application/json")
                                .content(json.writeValueAsString(Map.of("quoteId", quote))))
                .andReturn();
    }

    UUID buy(String who, List<String> offers) throws Exception {
        var result = checkout(quote(who, offers, ""), who, "buy");
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return UUID.fromString(
                json.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    @Test
    void multiSellerPurchaseAndOutbox() throws Exception {
        UUID id = buy("alice", List.of(OFFER, OFFER2));
        workflow.advance(id);
        assertEquals(
                "CONFIRMED", db.queryForObject("select status from orders where id=?", String.class, id));
        assertEquals(
                2,
                db.queryForObject(
                        "select count(*) from seller_orders where order_id=?", Integer.class, id));
        assertEquals(
                2,
                db.queryForObject(
                        "select count(*) from inventory where on_hand=99 and reserved=0", Integer.class));
        assertEquals(
                db.queryForObject("select total from orders where id=?", Long.class, id),
                db.queryForObject("select sum(net) from order_items where order_id=?", Long.class, id));
        workflow.relay();
        workflow.relay();
        assertEquals(2, db.queryForObject("select count(*) from notifications", Integer.class));
    }

    @Test
    void duplicateCheckoutAndChangedPayload() throws Exception {
        UUID q = quote("alice", List.of(OFFER), "");
        var a = checkout(q, "alice", "key");
        var b = checkout(q, "alice", "key");
        assertEquals(a.getResponse().getContentAsString(), b.getResponse().getContentAsString());
        assertEquals(409, checkout(UUID.randomUUID(), "alice", "key").getResponse().getStatus());
        assertEquals(1, db.queryForObject("select count(*) from orders", Integer.class));
    }

    @Test
    void concurrentLastUnit() throws Exception {
        db.update("update inventory set on_hand=1 where offer_id=?::uuid", OFFER);
        UUID a = quote("alice", List.of(OFFER), ""), b = quote("bob", List.of(OFFER), "");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            var x =
                    pool.submit(
                            () -> {
                                barrier.await();
                                return checkout(a, "alice", "a").getResponse().getStatus();
                            });
            var y =
                    pool.submit(
                            () -> {
                                barrier.await();
                                return checkout(b, "bob", "b").getResponse().getStatus();
                            });
            assertEquals(
                    List.of(200, 409), java.util.stream.Stream.of(x.get(), y.get()).sorted().toList());
        }
        assertEquals(
                1,
                db.queryForObject(
                        "select reserved from inventory where offer_id=?::uuid", Integer.class, OFFER));
        assertEquals(1, db.queryForObject("select count(*) from orders", Integer.class));
    }

    @Test
    void allReservationsRollbackOnSecondItemFailure() throws Exception {
        db.update("update inventory set on_hand=0 where offer_id=?::uuid", OFFER2);
        var r = checkout(quote("alice", List.of(OFFER, OFFER2), ""), "alice", "a");
        assertEquals(409, r.getResponse().getStatus());
        assertEquals(0, db.queryForObject("select sum(reserved) from inventory", Integer.class));
        assertEquals(0, db.queryForObject("select count(*) from orders", Integer.class));
    }

    @Test
    void quoteRejectsPriceChangeAndSuspension() throws Exception {
        UUID q = quote("alice", List.of(OFFER), "");
        db.update("update seller_offers set price=price+1 where id=?::uuid", OFFER);
        assertEquals(409, checkout(q, "alice", "a").getResponse().getStatus());
        db.update("update sellers set status='SUSPENDED'");
        assertEquals(409, checkout(q, "alice", "a").getResponse().getStatus());
    }

    @Test
    void concurrentCouponLimit() throws Exception {
        db.update("update promotions set max_uses=1 where code='WELCOME10'");
        UUID a = quote("alice", List.of(OFFER), "WELCOME10"),
                b = quote("bob", List.of(OFFER), "WELCOME10");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var x = pool.submit(() -> checkout(a, "alice", "a").getResponse().getStatus());
            var y = pool.submit(() -> checkout(b, "bob", "b").getResponse().getStatus());
            assertEquals(
                    List.of(200, 409), java.util.stream.Stream.of(x.get(), y.get()).sorted().toList());
        }
        assertEquals(
                1, db.queryForObject("select used from promotions where code='WELCOME10'", Integer.class));
    }

    @Test
    void lostCaptureResponseRecoveredWithoutDoubleCharge() throws Exception {
        UUID id = buy("alice", List.of(OFFER));
        long amount = db.queryForObject("select total from orders where id=?", Long.class, id);
        provider.capture(id.toString(), amount);
        workflow.advance(id);
        assertEquals(1, db.queryForObject("select count(*) from provider_payments", Integer.class));
        assertEquals(
                "CONFIRMED", db.queryForObject("select status from orders where id=?", String.class, id));
    }

    @Test
    void cancelledBeforeCaptureThenLateCaptureRefunds() throws Exception {
        UUID id = buy("alice", List.of(OFFER));
        send("/orders/" + id + "/cancel", Map.of(), "alice");
        workflow.advance(id);
        provider.capture(id.toString(), 299900);
        workflow.reconcile();
        UUID refund = db.queryForObject("select id from refunds", UUID.class);
        workflow.refund(refund);
        assertEquals(299900, db.queryForObject("select refunded from provider_payments", Long.class));
        assertEquals(
                100,
                db.queryForObject(
                        "select on_hand from inventory where offer_id=?::uuid", Integer.class, OFFER));
    }

    @Test
    void cancellationRetryAndProviderRefundRetry() throws Exception {
        UUID id = buy("alice", List.of(OFFER));
        workflow.advance(id);
        send("/orders/" + id + "/cancel", Map.of(), "alice");
        send("/orders/" + id + "/cancel", Map.of(), "alice");
        UUID refund = db.queryForObject("select id from refunds", UUID.class);
        provider.refund(id.toString(), "cancel:" + id, 299900);
        workflow.refund(refund);
        workflow.refund(refund);
        assertEquals(1, db.queryForObject("select count(*) from provider_refunds", Integer.class));
        assertEquals(
                100,
                db.queryForObject(
                        "select on_hand from inventory where offer_id=?::uuid", Integer.class, OFFER));
    }

    @Test
    void duplicateContradictoryWebhooksAndSignature() throws Exception {
        UUID id = buy("alice", List.of(OFFER));
        provider.capture(id.toString(), 299900);
        UUID payment = db.queryForObject("select id from payments where order_id=?", UUID.class, id);
        webhook("event1", payment, "CAPTURED", 200);
        webhook("event1", payment, "CAPTURED", 200);
        webhook("event2", payment, "FAILED", 200);
        assertEquals(
                "CONFIRMED", db.queryForObject("select status from orders where id=?", String.class, id));
        assertEquals(
                2, db.queryForObject("select count(*) from payment_webhook_events", Integer.class));
        mvc.perform(
                        post("/api/v1/payments/webhooks/local")
                                .header("X-Timestamp", java.time.Instant.now().getEpochSecond())
                                .header("X-Signature", "00")
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    void webhook(String event, UUID payment, String state, int status) throws Exception {
        String body =
                json.writeValueAsString(Map.of("eventId", event, "paymentId", payment, "status", state));
        long time = java.time.Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(
                new SecretKeySpec("test-webhook-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature =
                HexFormat.of().formatHex(mac.doFinal((time + "." + body).getBytes(StandardCharsets.UTF_8)));
        mvc.perform(
                        post("/api/v1/payments/webhooks/local")
                                .header("X-Timestamp", time)
                                .header("X-Signature", signature)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().is(status));
    }

    @Test
    void authorizationAndCsrf() throws Exception {
        UUID id = buy("alice", List.of(OFFER));
        mvc.perform(get("/api/v1/orders/" + id).with(user("bob"))).andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/v1/sellers/30000000-0000-0000-0000-000000000002/inventory/adjustments")
                                .with(user("seller").roles("SELLER"))
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                Map.of("offerId", OFFER2, "delta", 10, "reason", "attack"))))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/v1/cart/items")
                                .with(user("alice"))
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/sellers").with(user("alice"))).andExpect(status().isForbidden());
    }

    @Test
    void deliveryReturnInspectionAndDoubleRefundPrevention() throws Exception {
        UUID id = buy("alice", List.of(OFFER, OFFER2));
        workflow.advance(id);
        var item =
                db.queryForMap(
                        "select * from order_items where order_id=? and offer_id=?::uuid", id, OFFER);
        var sh =
                send(
                        "/seller-orders/" + item.get("seller_order_id") + "/shipments",
                        Map.of("itemId", item.get("id"), "carrier", "Local", "tracking", "TRACK1"),
                        "seller");
        assertEquals(
                "PARTIALLY_FULFILLED",
                db.queryForObject("select status from orders where id=?", String.class, id));
        mvc.perform(post("/api/v1/orders/" + id + "/cancel").with(user("alice")).with(csrf()))
                .andExpect(status().isConflict());
        send("/admin/shipments/" + sh.get("id").asText() + "/deliver", Map.of(), "admin");
        var r =
                send(
                        "/orders/" + id + "/returns",
                        Map.of("itemId", item.get("id"), "reason", "Not suitable"),
                        "alice");
        for (int n = 0; n < 2; n++)
            send(
                    "/admin/returns/" + r.get("id").asText() + "/inspect",
                    Map.of("accepted", true, "restock", true),
                    "admin");
        workflow.refund(db.queryForObject("select id from refunds", UUID.class));
        assertEquals(1, db.queryForObject("select count(*) from refunds", Integer.class));
        assertEquals(
                100,
                db.queryForObject(
                        "select on_hand from inventory where offer_id=?::uuid", Integer.class, OFFER));
        assertEquals(
                "PARTIALLY_REFUNDED",
                db.queryForObject("select status from payments where order_id=?", String.class, id));
    }

    @Test
    void refundCannotExceedCapturedIncludingPending() throws Exception {
        UUID id = buy("alice", List.of(OFFER));
        workflow.advance(id);
        UUID payment = db.queryForObject("select id from payments where order_id=?", UUID.class, id);
        var tx = new TransactionTemplate(manager);
        assertThrows(
                Exception.class,
                () ->
                        tx.executeWithoutResult(
                                t -> {
                                    db.queryForMap("select * from orders where id=? for update", id);
                                    workflow.queueRefund(payment, null, "excess", 299901);
                                }));
    }

    @Test
    void expiredReservationCompensates() throws Exception {
        UUID id = buy("alice", List.of(OFFER));
        db.update("update orders set expires_at=now()-interval '1 second' where id=?", id);
        workflow.advance(id);
        assertEquals(
                "CANCELLED", db.queryForObject("select status from orders where id=?", String.class, id));
        assertEquals(
                0,
                db.queryForObject(
                        "select reserved from inventory where offer_id=?::uuid", Integer.class, OFFER));
    }

    @Test
    void expiryDuringCaptureQueuesRefund() throws Exception {
        UUID id = buy("alice", List.of(OFFER));
        var paid = provider.capture(id.toString(), 299900);
        db.update("update orders set expires_at=now()-interval '1 second' where id=?", id);
        workflow.complete(id, paid);
        assertEquals(
                "CANCELLED", db.queryForObject("select status from orders where id=?", String.class, id));
        assertEquals(1, db.queryForObject("select count(*) from refunds", Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "select reserved from inventory where offer_id=?::uuid", Integer.class, OFFER));
    }

    @Test
    void providerDeclineReleasesStock() throws Exception {
        UUID id = buy("alice", List.of(OFFER));
        db.update(
                "insert into provider_payments(operation_key,amount,status,reference) values"
                        + " (?,299900,'FAILED',?)",
                id.toString(),
                UUID.randomUUID());
        workflow.advance(id);
        assertEquals(
                "FAILED",
                db.queryForObject("select status from payments where order_id=?", String.class, id));
        assertEquals(
                "CANCELLED", db.queryForObject("select status from orders where id=?", String.class, id));
        assertEquals(
                0,
                db.queryForObject(
                        "select reserved from inventory where offer_id=?::uuid", Integer.class, OFFER));
    }

    @Test
    void duplicateConcurrentCheckoutReturnsOneOrder() throws Exception {
        UUID q = quote("alice", List.of(OFFER), "");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var x = pool.submit(() -> checkout(q, "alice", "same"));
            var y = pool.submit(() -> checkout(q, "alice", "same"));
            assertEquals(200, x.get().getResponse().getStatus());
            assertEquals(200, y.get().getResponse().getStatus());
        }
        assertEquals(1, db.queryForObject("select count(*) from orders", Integer.class));
        assertEquals(
                1,
                db.queryForObject(
                        "select reserved from inventory where offer_id=?::uuid", Integer.class, OFFER));
    }

    @Test
    void cancelVersusCaptureCompletionNeverReconfirms() throws Exception {
        UUID id = buy("alice", List.of(OFFER));
        var paid = provider.capture(id.toString(), 299900);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var x =
                    pool.submit(
                            () -> {
                                workflow.complete(id, paid);
                                return true;
                            });
            var y = pool.submit(() -> send("/orders/" + id + "/cancel", Map.of(), "alice"));
            x.get();
            y.get();
        }
        workflow.reconcile();
        assertEquals(
                "CANCELLED", db.queryForObject("select status from orders where id=?", String.class, id));
        assertEquals(1, db.queryForObject("select count(*) from refunds", Integer.class));
        assertEquals(
                100,
                db.queryForObject(
                        "select on_hand from inventory where offer_id=?::uuid", Integer.class, OFFER));
    }

    @Test
    void pendingRefundsReserveBudget() throws Exception {
        UUID id = buy("alice", List.of(OFFER));
        workflow.advance(id);
        UUID payment = db.queryForObject("select id from payments where order_id=?", UUID.class, id);
        var tx = new TransactionTemplate(manager);
        tx.executeWithoutResult(
                t -> {
                    db.queryForMap("select * from orders where id=? for update", id);
                    workflow.queueRefund(payment, null, "first", 200000);
                });
        assertThrows(
                Exception.class,
                () ->
                        tx.executeWithoutResult(
                                t -> {
                                    db.queryForMap("select * from orders where id=? for update", id);
                                    workflow.queueRefund(payment, null, "second", 100000);
                                }));
        assertEquals(1, db.queryForObject("select count(*) from refunds", Integer.class));
    }

    @Test
    void exhaustedWorkflowNeedsManualRetry() throws Exception {
        UUID id = buy("alice", List.of(OFFER));
        db.update("update checkout_workflows set attempts=8 where order_id=?", id);
        workflow.advance(id);
        assertEquals(
                "MANUAL_REVIEW",
                db.queryForObject(
                        "select status from checkout_workflows where order_id=?", String.class, id));
        send("/admin/workflows/" + id + "/retry", Map.of(), "admin");
        workflow.advance(id);
        assertEquals(
                "CONFIRMED", db.queryForObject("select status from orders where id=?", String.class, id));
    }

    @Test
    void uncapturedPaymentCannotReserveRefund() throws Exception {
        UUID id = buy("alice", List.of(OFFER));
        UUID payment = db.queryForObject("select id from payments where order_id=?", UUID.class, id);
        var tx = new TransactionTemplate(manager);
        assertThrows(
                Exception.class,
                () ->
                        tx.executeWithoutResult(
                                t -> {
                                    db.queryForMap("select * from orders where id=? for update", id);
                                    workflow.queueRefund(payment, null, "uncaptured", 100);
                                }));
        assertEquals(0, db.queryForObject("select count(*) from refunds", Integer.class));
    }

    JsonNode quantityPost(String path, Object body, String key, String who) throws Exception {
        return json.readTree(
                mvc.perform(
                                post("/api/v1" + path)
                                        .with(
                                                user(who)
                                                        .roles(
                                                                who.equals("admin")
                                                                        ? "ADMIN"
                                                                        : who.equals("seller") ? "SELLER" : "CUSTOMER"))
                                        .with(csrf())
                                        .header("Idempotency-Key", key)
                                        .contentType("application/json")
                                        .content(json.writeValueAsString(body)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    UUID quantityOrder(int quantity) throws Exception {
        quote("alice", List.of(OFFER), "");
        send("/cart/items", Map.of("offerId", OFFER, "quantity", quantity), "alice");
        UUID address =
                db.queryForObject("select id from addresses where customer_id='alice' limit 1", UUID.class);
        UUID q =
                UUID.fromString(
                        send("/checkout/quotes", Map.of("addressId", address, "coupon", "WELCOME10"), "alice")
                                .get("id")
                                .asText());
        var response = checkout(q, "alice", "quantities");
        assertEquals(200, response.getResponse().getStatus());
        UUID order =
                UUID.fromString(
                        json.readTree(response.getResponse().getContentAsString()).get("id").asText());
        workflow.advance(order);
        return order;
    }

    @Test
    void splitShipmentsAndPartialReturnsConserveQuantityAndRefunds() throws Exception {
        db.update("update promotions set percent=33 where code='WELCOME10'");
        db.update("update seller_offers set price=101 where id=?::uuid", OFFER);
        UUID order = quantityOrder(3);
        var item = db.queryForMap("select * from order_items where order_id=?", order);
        String path = "/seller-orders/" + item.get("seller_order_id") + "/shipments";
        var a =
                quantityPost(
                        path,
                        Map.of("itemId", item.get("id"), "quantity", 1, "carrier", "Local", "tracking", "A"),
                        "ship-a",
                        "seller");
        var b =
                quantityPost(
                        path,
                        Map.of("itemId", item.get("id"), "quantity", 2, "carrier", "Local", "tracking", "B"),
                        "ship-b",
                        "seller");
        assertEquals(
                a.get("id"),
                quantityPost(
                        path,
                        Map.of(
                                "itemId", item.get("id"), "quantity", 1, "carrier", "Local", "tracking", "A"),
                        "ship-a",
                        "seller")
                        .get("id"));
        send("/admin/shipments/" + a.get("id").asText() + "/deliver", Map.of(), "admin");
        assertEquals(
                "PARTIALLY_FULFILLED",
                db.queryForObject("select status from orders where id=?", String.class, order));
        send("/admin/shipments/" + b.get("id").asText() + "/deliver", Map.of(), "admin");
        send("/admin/shipments/" + b.get("id").asText() + "/deliver", Map.of(), "admin");
        assertEquals(
                "FULFILLED",
                db.queryForObject("select status from orders where id=?", String.class, order));
        var r1 =
                quantityPost(
                        "/orders/" + order + "/returns",
                        Map.of(
                                "itemId",
                                item.get("id"),
                                "shipmentId",
                                a.get("id").asText(),
                                "quantity",
                                1,
                                "reason",
                                "Small return"),
                        "r1",
                        "alice");
        var r2 =
                quantityPost(
                        "/orders/" + order + "/returns",
                        Map.of(
                                "itemId",
                                item.get("id"),
                                "shipmentId",
                                b.get("id").asText(),
                                "quantity",
                                2,
                                "reason",
                                "Remaining return"),
                        "r2",
                        "alice");
        for (var r : List.of(r1, r2)) {
            send(
                    "/admin/returns/" + r.get("id").asText() + "/inspect",
                    Map.of("accepted", true, "restock", true),
                    "admin");
            send(
                    "/admin/returns/" + r.get("id").asText() + "/inspect",
                    Map.of("accepted", true, "restock", true),
                    "admin");
        }
        for (var refund : db.queryForList("select id from refunds"))
            workflow.refund((UUID) refund.get("id"));
        assertEquals(
                203,
                db.queryForObject("select refunded from payments where order_id=?", Long.class, order));
        assertEquals(
                100,
                db.queryForObject(
                        "select on_hand from inventory where offer_id=?::uuid", Integer.class, OFFER));
        assertEquals(
                3,
                db.queryForObject(
                        "select returned_quantity from order_items where order_id=?", Integer.class, order));
    }

    @Test
    void concurrentPartialShipmentsCannotExceedPurchasedQuantity() throws Exception {
        UUID order = quantityOrder(3);
        var item = db.queryForMap("select * from order_items where order_id=?", order);
        String path = "/api/v1/seller-orders/" + item.get("seller_order_id") + "/shipments";
        String body =
                json.writeValueAsString(
                        Map.of(
                                "itemId", item.get("id"), "quantity", 2, "carrier", "Local", "tracking", "PART"));
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> a =
                    () ->
                            mvc.perform(
                                            post(path)
                                                    .with(user("seller").roles("SELLER"))
                                                    .with(csrf())
                                                    .header("Idempotency-Key", UUID.randomUUID())
                                                    .contentType("application/json")
                                                    .content(body))
                                    .andReturn()
                                    .getResponse()
                                    .getStatus();
            var x = pool.submit(a);
            var y = pool.submit(a);
            assertEquals(
                    List.of(200, 409), java.util.stream.Stream.of(x.get(), y.get()).sorted().toList());
        }
        assertEquals(
                2,
                db.queryForObject(
                        "select shipped_quantity from order_items where order_id=?", Integer.class, order));
    }

    @Test
    void concurrentReturnRequestsCannotExceedDeliveredQuantity() throws Exception {
        UUID order = quantityOrder(3);
        var item = db.queryForMap("select * from order_items where order_id=?", order);
        var sh =
                quantityPost(
                        "/seller-orders/" + item.get("seller_order_id") + "/shipments",
                        Map.of("itemId", item.get("id"), "quantity", 3, "carrier", "Local", "tracking", "ALL"),
                        "ship",
                        "seller");
        send("/admin/shipments/" + sh.get("id").asText() + "/deliver", Map.of(), "admin");
        String body =
                json.writeValueAsString(
                        Map.of("itemId", item.get("id"), "quantity", 2, "reason", "Return"));
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> attempt =
                    () ->
                            mvc.perform(
                                            post("/api/v1/orders/" + order + "/returns")
                                                    .with(user("alice"))
                                                    .with(csrf())
                                                    .header("Idempotency-Key", UUID.randomUUID())
                                                    .contentType("application/json")
                                                    .content(body))
                                    .andReturn()
                                    .getResponse()
                                    .getStatus();
            var a = pool.submit(attempt);
            var b = pool.submit(attempt);
            assertEquals(
                    List.of(200, 409), java.util.stream.Stream.of(a.get(), b.get()).sorted().toList());
        }
        assertEquals(
                2,
                db.queryForObject(
                        "select returned_quantity from order_items where order_id=?", Integer.class, order));
    }

    @Test
    void changedShipmentKeyPayloadIsRejected() throws Exception {
        UUID order = quantityOrder(3);
        var item = db.queryForMap("select * from order_items where order_id=?", order);
        String path = "/seller-orders/" + item.get("seller_order_id") + "/shipments";
        quantityPost(
                path,
                Map.of("itemId", item.get("id"), "quantity", 1, "carrier", "Local", "tracking", "KEY"),
                "stable",
                "seller");
        mvc.perform(
                        post("/api/v1" + path)
                                .with(user("seller").roles("SELLER"))
                                .with(csrf())
                                .header("Idempotency-Key", "stable")
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "itemId",
                                                        item.get("id"),
                                                        "quantity",
                                                        2,
                                                        "carrier",
                                                        "Local",
                                                        "tracking",
                                                        "KEY"))))
                .andExpect(status().isConflict());
    }
}
