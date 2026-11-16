package com.example.marketplace;

import com.example.marketplace.checkout.Workflow;
import com.example.marketplace.delivery.TrackingWorker;
import com.example.marketplace.labels.LabelService;
import com.example.marketplace.reporting.TaxReporting;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
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
@AutoConfigureMockMvc
@SpringBootTest(
        properties = {
                "LOCAL_PASSWORD=test-only",
                "marketplace.webhook-secret=test-only",
                "marketplace.shipping.provider=shippo",
                "marketplace.shippo.token=shippo_test_contract",
                "marketplace.tax.provider=stripe",
                "marketplace.tax.stripe-secret-key=sk_test_tax_contract",
                "marketplace.providers.timeout-ms=800",
                "marketplace.worker-initial-delay=3600000"
        })
class ShippingTaxIntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpServer provider = start();
    static final AtomicBoolean taxDown = new AtomicBoolean(),
            trackingDown = new AtomicBoolean(),
            badTax = new AtomicBoolean(),
            wrongCurrency = new AtomicBoolean(),
            slowTax = new AtomicBoolean(),
            holdTax = new AtomicBoolean();
    static final Map<String, Map<String, Object>> remoteLabels = new ConcurrentHashMap<>(),
            remoteTax = new ConcurrentHashMap<>();
    static final Map<String, Map<String, Object>> calculations = new ConcurrentHashMap<>();
    static final AtomicBoolean loseLabel = new AtomicBoolean(),
            loseSale = new AtomicBoolean(),
            loseReversal = new AtomicBoolean();
    static final AtomicInteger labelPosts = new AtomicInteger(),
            salePosts = new AtomicInteger(),
            reversalPosts = new AtomicInteger();
    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");
    static volatile CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1);
    @Autowired
    JdbcTemplate db;
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    Workflow workflow;
    @Autowired
    TrackingWorker tracking;
    @Autowired
    LabelService labels;
    @Autowired
    TaxReporting reporting;

    static HttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/", ShippingTaxIntegrationTest::handle);
            server.start();
            return server;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void handle(HttpExchange x) throws java.io.IOException {
        try {
            Map<String, Object> response;
            String path = x.getRequestURI().getPath();
            if (path.equals("/shipments/")) {
                assertEquals(
                        "ShippoToken shippo_test_contract", x.getRequestHeaders().getFirst("Authorization"));
                var request = JSON.readTree(x.getRequestBody().readAllBytes());
                assertEquals("g", request.path("parcels").get(0).path("mass_unit").asText());
                response =
                        Map.of(
                                "object_id",
                                "shippo_quote_contract",
                                "test",
                                true,
                                "rates",
                                List.of(
                                        Map.of(
                                                "object_id",
                                                "rate_contract",
                                                "amount",
                                                "12.34",
                                                "currency",
                                                wrongCurrency.get() ? "USD" : "INR",
                                                "provider",
                                                "Contract Carrier")));
            } else if (path.equals("/v1/tax/calculations")) {
                assertEquals(
                        "Bearer sk_test_tax_contract", x.getRequestHeaders().getFirst("Authorization"));
                var form = form(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                assertEquals("inr", form.get("currency"));
                assertEquals("shipping", form.get("customer_details[address_source]"));
                if (taxDown.get()) {
                    x.sendResponseHeaders(503, -1);
                    return;
                }
                if (slowTax.get()) Thread.sleep(1300);
                if (holdTax.get()) {
                    entered.countDown();
                    resume.await(2, TimeUnit.SECONDS);
                }
                List<Map<String, Object>> lines = new ArrayList<>();
                long net = 0, totalTax = 0;
                for (int i = 0; form.containsKey("line_items[" + i + "][amount]"); i++) {
                    String p = "line_items[" + i + "]";
                    long amount = Long.parseLong(form.get(p + "[amount]"));
                    long tax = amount * 7 / 100;
                    net += amount;
                    totalTax += tax;
                    lines.add(
                            Map.of(
                                    "reference", form.get(p + "[reference]"), "amount", amount, "amount_tax", tax));
                }
                long ship = Long.parseLong(form.get("shipping_cost[amount]")), shipTax = ship * 7 / 100;
                response =
                        Map.of(
                                "id",
                                "taxcalc_contract",
                                "livemode",
                                false,
                                "currency",
                                "inr",
                                "amount_total",
                                net + ship + totalTax + shipTax,
                                "tax_amount_exclusive",
                                totalTax + shipTax + (badTax.get() ? 1 : 0),
                                "tax_amount_inclusive",
                                0,
                                "shipping_cost",
                                Map.of("amount_tax", shipTax),
                                "line_items",
                                Map.of("has_more", false, "data", lines),
                                "expires_at",
                                Instant.now().plusSeconds(300).getEpochSecond());
                calculations.put("taxcalc_contract", response);
            } else if (path.equals("/transactions/") && x.getRequestMethod().equals("POST")) {
                var body = JSON.readTree(x.getRequestBody().readAllBytes());
                labelPosts.incrementAndGet();
                String operation = body.path("metadata").asText();
                response =
                        remoteLabels.computeIfAbsent(
                                operation,
                                key ->
                                        new ConcurrentHashMap<>(
                                                Map.of(
                                                        "object_id",
                                                        "label_" + UUID.randomUUID().toString().replace("-", ""),
                                                        "metadata",
                                                        key,
                                                        "test",
                                                        true,
                                                        "status",
                                                        "SUCCESS",
                                                        "rate",
                                                        rate(),
                                                        "tracking_number",
                                                        "TRACK-LABEL-" + labelPosts.get(),
                                                        "label_url",
                                                        "https://shippo-delivery.s3.amazonaws.com/label.pdf")));
                if (loseLabel.getAndSet(false)) Thread.sleep(1300);
            } else if (path.equals("/transactions/")) {
                response = Map.of("results", new ArrayList<>(remoteLabels.values()), "next", "");
            } else if (path.startsWith("/transactions/")) {
                String id = path.split("/")[2];
                response =
                        remoteLabels.values().stream()
                                .filter(value -> id.equals(value.get("object_id")))
                                .findFirst()
                                .orElseThrow();
            } else if (path.startsWith("/rates/")) {
                response = rate();
            } else if (path.startsWith("/carrier_accounts/")) {
                response = Map.of("carrier", "usps");
            } else if (path.equals("/v1/tax/transactions/create_from_calculation")) {
                salePosts.incrementAndGet();
                var form = form(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String key = x.getRequestHeaders().getFirst("Idempotency-Key");
                assertEquals(form.get("reference"), key);
                response = remoteTax.computeIfAbsent(key, k -> sale(form));
                if (loseSale.getAndSet(false)) Thread.sleep(1300);
            } else if (path.equals("/v1/tax/transactions/create_reversal")) {
                reversalPosts.incrementAndGet();
                var form = form(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String key = x.getRequestHeaders().getFirst("Idempotency-Key");
                assertEquals(form.get("reference"), key);
                response = remoteTax.computeIfAbsent(key, k -> reversal(form));
                if (loseReversal.getAndSet(false)) Thread.sleep(1300);
            } else if (path.startsWith("/v1/tax/transactions/")) {
                String id = path.split("/")[4];
                response =
                        remoteTax.values().stream()
                                .filter(value -> id.equals(value.get("id")))
                                .findFirst()
                                .orElseThrow();
            } else if (path.startsWith("/tracks/")) {
                if (trackingDown.getAndSet(false)) {
                    x.sendResponseHeaders(503, -1);
                    return;
                }
                String[] parts = path.split("/");
                response =
                        Map.of(
                                "carrier",
                                parts[2],
                                "tracking_number",
                                parts[3],
                                "tracking_status",
                                Map.of(
                                        "status",
                                        "DELIVERED",
                                        "status_date",
                                        Instant.now().minusSeconds(60).toString()));
            } else {
                x.sendResponseHeaders(404, -1);
                return;
            }
            byte[] raw = JSON.writeValueAsBytes(response);
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(200, raw.length);
            x.getResponseBody().write(raw);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            x.close();
        }
    }

    static Map<String, String> form(String raw) {
        Map<String, String> map = new HashMap<>();
        for (String pair : raw.split("&")) {
            var p = pair.split("=", 2);
            map.put(
                    URLDecoder.decode(p[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(p[1], StandardCharsets.UTF_8));
        }
        return map;
    }

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
        r.add(
                "marketplace.shippo.api-base", () -> "http://127.0.0.1:" + provider.getAddress().getPort());
        r.add("marketplace.tax.api-base", () -> "http://127.0.0.1:" + provider.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        provider.stop(0);
    }

    static Map<String, Object> rate() {
        return Map.of(
                "object_id",
                "rate_contract",
                "amount",
                "12.34",
                "currency",
                "INR",
                "carrier_account",
                "account_contract");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> sale(Map<String, String> form) {
        var calculation = calculations.get(form.get("calculation"));
        assertNotNull(calculation);
        var entries =
                (List<Map<String, Object>>)
                        ((Map<String, Object>) calculation.get("line_items")).get("data");
        List<Map<String, Object>> lines = new ArrayList<>();
        for (var entry : entries) {
            var line = new HashMap<>(entry);
            line.put("id", "tax_li_" + entry.get("reference").toString().replace("-", ""));
            lines.add(line);
        }
        return new ConcurrentHashMap<>(
                Map.of(
                        "id",
                        "tax_" + UUID.randomUUID().toString().replace("-", ""),
                        "reference",
                        form.get("reference"),
                        "livemode",
                        false,
                        "type",
                        "transaction",
                        "currency",
                        "inr",
                        "line_items",
                        Map.of("data", lines, "has_more", false),
                        "shipping_cost",
                        Map.of("amount", 1234, "amount_tax", 86)));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> reversal(Map<String, String> form) {
        var original =
                remoteTax.values().stream()
                        .filter(value -> form.get("original_transaction").equals(value.get("id")))
                        .findFirst()
                        .orElseThrow();
        var sources =
                (List<Map<String, Object>>) ((Map<String, Object>) original.get("line_items")).get("data");
        List<Map<String, Object>> lines = new ArrayList<>();
        if (form.get("mode").equals("full")) {
            for (var source : sources)
                lines.add(
                        Map.of(
                                "id",
                                "tax_li_reversal_" + lines.size(),
                                "reference",
                                source.get("reference"),
                                "amount",
                                -((Number) source.get("amount")).longValue(),
                                "amount_tax",
                                -((Number) source.get("amount_tax")).longValue(),
                                "reversal",
                                Map.of("original_line_item", source.get("id"))));
        } else {
            for (int n = 0; form.containsKey("line_items[" + n + "][amount]"); n++) {
                String p = "line_items[" + n + "]";
                String sourceId = form.get(p + "[original_line_item]");
                assertTrue(sources.stream().anyMatch(source -> sourceId.equals(source.get("id"))));
                lines.add(
                        Map.of(
                                "id",
                                "tax_li_reversal_" + n,
                                "reference",
                                form.get(p + "[reference]"),
                                "amount",
                                Long.parseLong(form.get(p + "[amount]")),
                                "amount_tax",
                                Long.parseLong(form.get(p + "[amount_tax]")),
                                "reversal",
                                Map.of("original_line_item", sourceId)));
            }
        }
        return new ConcurrentHashMap<>(
                Map.of(
                        "id",
                        "tax_" + UUID.randomUUID().toString().replace("-", ""),
                        "reference",
                        form.get("reference"),
                        "livemode",
                        false,
                        "type",
                        "reversal",
                        "currency",
                        "inr",
                        "reversal",
                        Map.of("original_transaction", original.get("id")),
                        "line_items",
                        Map.of("data", lines, "has_more", false),
                        "shipping_cost",
                        Map.of(
                                "amount",
                                form.get("mode").equals("full") ? -1234 : 0,
                                "amount_tax",
                                form.get("mode").equals("full") ? -86 : 0)));
    }

    @BeforeEach
    void reset() {
        db.execute("truncate customers cascade");
        db.execute("truncate outbox_events,processed_events,consumer_versions,event_failures");
        db.update("update inventory set on_hand=100,reserved=0");
        db.update(
                "update product_variants set"
                        + " attributes='{\"weightGrams\":\"1000\",\"taxCode\":\"txcd_99999999\"}'::jsonb");
        db.update(
                "insert into seller_shipping_profiles values"
                        + " ('30000000-0000-0000-0000-000000000001','{\"name\":\"Seller\",\"street1\":\"1"
                        + " Origin"
                        + " Road\",\"city\":\"Pune\",\"state\":\"MH\",\"zip\":\"411001\",\"country\":\"IN\"}',20,20,20,10000,0)"
                        + " on conflict do nothing");
        taxDown.set(false);
        trackingDown.set(false);
        badTax.set(false);
        wrongCurrency.set(false);
        slowTax.set(false);
        holdTax.set(false);
        db.update(
                "update seller_offers set price=299900 where id='40000000-0000-0000-0000-000000000001'");
        remoteLabels.clear();
        remoteTax.clear();
        calculations.clear();
        loseLabel.set(false);
        loseSale.set(false);
        loseReversal.set(false);
        labelPosts.set(0);
        salePosts.set(0);
        reversalPosts.set(0);
        entered = new CountDownLatch(1);
        resume = new CountDownLatch(1);
    }

    UUID cart() throws Exception {
        db.update("insert into customers(id,display_name) values ('tax-user','Tax User')");
        UUID address = UUID.randomUUID();
        db.update(
                "insert into addresses values (?,'tax-user','1 Destination','Mumbai','400001','IN')",
                address);
        db.update("insert into address_details values (?,'MH')", address);
        db.update("insert into carts(customer_id) values ('tax-user')");
        db.update(
                "insert into cart_items values (?,'tax-user','40000000-0000-0000-0000-000000000001',2)",
                UUID.randomUUID());
        return address;
    }

    MvcResult quote(UUID address) throws Exception {
        return mvc.perform(
                        post("/api/v1/checkout/quotes")
                                .with(user("tax-user"))
                                .with(csrf())
                                .contentType("application/json")
                                .content(json.writeValueAsString(Map.of("addressId", address))))
                .andReturn();
    }

    @Test
    void realWireQuoteTaxShippingAndPartialRefundAreConserved() throws Exception {
        UUID address = cart();
        var response = quote(address);
        assertEquals(
                200, response.getResponse().getStatus(), response.getResponse().getContentAsString());
        var q = json.readTree(response.getResponse().getContentAsString());
        assertEquals(1234, q.get("shipping").asLong());
        assertEquals(42072, q.get("tax").asLong());
        assertEquals(643106, q.get("total").asLong());
        var checkout =
                mvc.perform(
                                post("/api/v1/checkouts")
                                        .with(user("tax-user"))
                                        .with(csrf())
                                        .header("Idempotency-Key", "tax-order")
                                        .contentType("application/json")
                                        .content(json.writeValueAsString(Map.of("quoteId", q.get("id").asText()))))
                        .andExpect(status().isOk())
                        .andReturn();
        UUID order =
                UUID.fromString(
                        json.readTree(checkout.getResponse().getContentAsString()).get("id").asText());
        workflow.advance(order);
        var item = db.queryForMap("select * from order_items where order_id=?", order);
        var ship =
                mvc.perform(
                                post("/api/v1/seller-orders/" + item.get("seller_order_id") + "/shipments")
                                        .with(user("seller").roles("SELLER"))
                                        .with(csrf())
                                        .header("Idempotency-Key", "partial-tax")
                                        .contentType("application/json")
                                        .content(
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "itemId",
                                                                item.get("id"),
                                                                "quantity",
                                                                1,
                                                                "carrier",
                                                                "usps",
                                                                "tracking",
                                                                "TRACK1"))))
                        .andExpect(status().isOk())
                        .andReturn();
        String shipment = json.readTree(ship.getResponse().getContentAsString()).get("id").asText();
        trackingDown.set(true);
        tracking.poll();
        assertEquals(
                "SHIPPED",
                db.queryForObject("select status from shipments where id=?::uuid", String.class, shipment));
        db.update("update shipments set tracking_next_attempt=now()");
        tracking.poll();
        tracking.poll();
        assertEquals(
                1,
                db.queryForObject(
                        "select delivered_quantity from order_items where order_id=?", Integer.class, order));
        var returned =
                mvc.perform(
                                post("/api/v1/orders/" + order + "/returns")
                                        .with(user("tax-user"))
                                        .with(csrf())
                                        .header("Idempotency-Key", "unit-return")
                                        .contentType("application/json")
                                        .content(
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "itemId",
                                                                item.get("id"),
                                                                "quantity",
                                                                1,
                                                                "shipmentId",
                                                                shipment,
                                                                "reason",
                                                                "Partial"))))
                        .andExpect(status().isOk())
                        .andReturn();
        String rid = json.readTree(returned.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(
                        post("/api/v1/admin/returns/" + rid + "/inspect")
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"accepted\":true,\"restock\":true}"))
                .andExpect(status().isOk());
        workflow.refund(db.queryForObject("select id from refunds", UUID.class));
        assertEquals(
                320893,
                db.queryForObject("select refunded from payments where order_id=?", Long.class, order));
    }

    @Test
    void quoteFailureLeavesNoQuoteOrderOrReservationAndCanRetry() throws Exception {
        UUID address = cart();
        taxDown.set(true);
        assertEquals(503, quote(address).getResponse().getStatus());
        assertEquals(0, db.queryForObject("select count(*) from checkout_quotes", Integer.class));
        assertEquals(0, db.queryForObject("select sum(reserved) from inventory", Integer.class));
        taxDown.set(false);
        assertEquals(200, quote(address).getResponse().getStatus());
    }

    @Test
    void inconsistentTaxAndForeignCurrencyRatesAreRejected() throws Exception {
        UUID address = cart();
        badTax.set(true);
        assertEquals(409, quote(address).getResponse().getStatus());
        badTax.set(false);
        wrongCurrency.set(true);
        assertEquals(409, quote(address).getResponse().getStatus());
        assertEquals(0, db.queryForObject("select count(*) from checkout_quotes", Integer.class));
    }

    @Test
    void providerTimeoutLeavesCartDurable() throws Exception {
        UUID address = cart();
        slowTax.set(true);
        assertEquals(503, quote(address).getResponse().getStatus());
        assertEquals(1, db.queryForObject("select count(*) from cart_items", Integer.class));
        assertEquals(0, db.queryForObject("select count(*) from checkout_quotes", Integer.class));
    }

    @Test
    void slowProviderDoesNotHoldCartLockAndChangedCartRejectsQuote() throws Exception {
        UUID address = cart();
        holdTax.set(true);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var result = pool.submit(() -> quote(address));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            try {
                var mutation =
                        pool.submit(
                                () ->
                                        mvc.perform(
                                                        post("/api/v1/cart/items")
                                                                .with(user("tax-user"))
                                                                .with(csrf())
                                                                .contentType("application/json")
                                                                .content(
                                                                        "{\"offerId\":\"40000000-0000-0000-0000-000000000001\",\"quantity\":3}"))
                                                .andReturn());
                assertEquals(200, mutation.get(500, TimeUnit.MILLISECONDS).getResponse().getStatus());
            } finally {
                resume.countDown();
            }
            assertEquals(409, result.get().getResponse().getStatus());
        }
        assertEquals(0, db.queryForObject("select count(*) from checkout_quotes", Integer.class));
    }

    PurchaseOrder paidOrder(int quantity, long price) throws Exception {
        UUID address = cart();
        db.update("update cart_items set quantity=?", quantity);
        db.update(
                "update seller_offers set price=? where id='40000000-0000-0000-0000-000000000001'", price);
        var q = quote(address);
        assertEquals(200, q.getResponse().getStatus(), q.getResponse().getContentAsString());
        String quote = json.readTree(q.getResponse().getContentAsString()).get("id").asText();
        var response = postJson("/checkouts", Map.of("quoteId", quote), "tax-user", "order-key");
        UUID order = UUID.fromString(response.path("id").asText());
        workflow.advance(order);
        var item = db.queryForMap("select * from order_items where order_id=?", order);
        return new PurchaseOrder(order, (UUID) item.get("id"), (UUID) item.get("seller_order_id"));
    }

    JsonNode postJson(String path, Object body, String who, String key) throws Exception {
        var request =
                post("/api/v1" + path)
                        .with(
                                user(who)
                                        .roles(
                                                who.equals("admin")
                                                        ? "ADMIN"
                                                        : who.equals("seller") ? "SELLER" : "CUSTOMER"))
                        .with(csrf())
                        .contentType("application/json")
                        .content(json.writeValueAsString(body));
        if (key != null) request.header("Idempotency-Key", key);
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    UUID label(PurchaseOrder order, int quantity, long ceiling, String key) throws Exception {
        return UUID.fromString(
                postJson(
                        "/seller-orders/" + order.group() + "/labels",
                        Map.of("itemId", order.item(), "quantity", quantity, "maxCostMinor", ceiling),
                        "seller",
                        key)
                        .path("id")
                        .asText());
    }

    void labelDue(UUID id) {
        db.update("update shipping_labels set next_attempt=now()-interval '1 second' where id=?", id);
    }

    void taxDue(UUID id) {
        db.update(
                "update tax_reporting_jobs set next_attempt=now()-interval '1 second' where id=?", id);
    }

    UUID saleJob() {
        return db.queryForObject("select id from tax_reporting_jobs where kind='SALE'", UUID.class);
    }

    UUID cancellation(PurchaseOrder order) throws Exception {
        postJson("/orders/" + order.order() + "/cancel", Map.of(), "tax-user", null);
        UUID refund = db.queryForObject("select id from refunds", UUID.class);
        workflow.refund(refund);
        return db.queryForObject("select id from tax_reporting_jobs where kind='REVERSAL'", UUID.class);
    }

    UUID receivedReturn(PurchaseOrder order, UUID shipment, int quantity, String key)
            throws Exception {
        var r =
                postJson(
                        "/orders/" + order.order() + "/returns",
                        Map.of(
                                "itemId",
                                order.item(),
                                "shipmentId",
                                shipment,
                                "quantity",
                                quantity,
                                "reason",
                                "Return"),
                        "tax-user",
                        key);
        UUID id = UUID.fromString(r.path("id").asText());
        postJson(
                "/admin/returns/" + id + "/inspect",
                Map.of("accepted", true, "restock", true),
                "admin",
                null);
        return id;
    }

    UUID shipAll(PurchaseOrder order, int quantity) throws Exception {
        var sh =
                postJson(
                        "/seller-orders/" + order.group() + "/shipments",
                        Map.of(
                                "itemId",
                                order.item(),
                                "quantity",
                                quantity,
                                "carrier",
                                "usps",
                                "tracking",
                                "ALL-UNITS"),
                        "seller",
                        "ship-all");
        tracking.poll();
        return UUID.fromString(sh.path("id").asText());
    }

    @Test
    void labelPurchaseRetryDispatchAndRefreshHaveOnePurchase() throws Exception {
        var order = paidOrder(2, 299900);
        UUID label = label(order, 1, 2000, "label-key");
        assertEquals(label, label(order, 1, 2000, "label-key"));
        labels.process(label);
        labels.process(label);
        assertEquals(1, labelPosts.get());
        assertEquals(
                "PURCHASED",
                db.queryForObject("select status from shipping_labels where id=?", String.class, label));
        assertEquals(
                0,
                db.queryForObject(
                        "select shipped_quantity from order_items where id=?", Integer.class, order.item()));
        postJson("/shipping-labels/" + label + "/refresh", Map.of(), "seller", null);
        var a = postJson("/shipping-labels/" + label + "/dispatch", Map.of(), "seller", null);
        var b = postJson("/shipping-labels/" + label + "/dispatch", Map.of(), "seller", null);
        assertEquals(a.path("id"), b.path("id"));
        assertEquals(
                1,
                db.queryForObject(
                        "select shipped_quantity from order_items where id=?", Integer.class, order.item()));
        assertEquals(1, labelPosts.get());
    }

    @Test
    void lostLabelPurchaseResponseReconcilesWithoutAnotherPost() throws Exception {
        var order = paidOrder(2, 299900);
        UUID label = label(order, 1, 2000, "lost");
        loseLabel.set(true);
        assertThrows(Exception.class, () -> labels.process(label));
        assertEquals(
                "ATTEMPTED",
                db.queryForObject("select status from shipping_labels where id=?", String.class, label));
        labelDue(label);
        labels.process(label);
        assertEquals(1, labelPosts.get());
        assertEquals(
                "PURCHASED",
                db.queryForObject("select status from shipping_labels where id=?", String.class, label));
    }

    @Test
    void ambiguousMarkerWithoutRemoteLabelNeverPostsAgain() throws Exception {
        var order = paidOrder(2, 299900);
        UUID label = label(order, 1, 2000, "ambiguous");
        db.update(
                "update shipping_labels set"
                        + " status='ATTEMPTED',rate_id='rate_contract',purchase_started_at=now() where id=?",
                label);
        labels.process(label);
        assertEquals(0, labelPosts.get());
        db.update("update shipping_labels set attempts=8,next_attempt=now() where id=?", label);
        labels.process(label);
        assertEquals(
                "MANUAL_REVIEW",
                db.queryForObject("select status from shipping_labels where id=?", String.class, label));
        postJson("/admin/shipping-labels/" + label + "/retry", Map.of(), "admin", null);
        labels.process(label);
        assertEquals(0, labelPosts.get());
        mvc.perform(
                        post("/api/v1/shipping-labels/" + label + "/abort")
                                .with(user("seller").roles("SELLER"))
                                .with(csrf()))
                .andExpect(status().isConflict());
        mvc.perform(
                        post("/api/v1/orders/" + order.order() + "/cancel").with(user("tax-user")).with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    void ceilingRejectsPurchaseAndAbortReleasesUnits() throws Exception {
        var order = paidOrder(2, 299900);
        UUID label = label(order, 2, 100, "ceiling");
        assertThrows(Exception.class, () -> labels.process(label));
        assertEquals(0, labelPosts.get());
        postJson("/shipping-labels/" + label + "/abort", Map.of(), "seller", null);
        assertEquals(
                0,
                db.queryForObject(
                        "select label_reserved_quantity from order_items where id=?",
                        Integer.class,
                        order.item()));
        postJson("/orders/" + order.order() + "/cancel", Map.of(), "tax-user", null);
    }

    @Test
    void concurrentLabelRequestsCannotAllocateSameUnits() throws Exception {
        var order = paidOrder(2, 299900);
        String body =
                json.writeValueAsString(
                        Map.of("itemId", order.item(), "quantity", 2, "maxCostMinor", 2000));
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> request =
                    () ->
                            mvc.perform(
                                            post("/api/v1/seller-orders/" + order.group() + "/labels")
                                                    .with(user("seller").roles("SELLER"))
                                                    .with(csrf())
                                                    .header("Idempotency-Key", UUID.randomUUID())
                                                    .contentType("application/json")
                                                    .content(body))
                                    .andReturn()
                                    .getResponse()
                                    .getStatus();
            var a = pool.submit(request);
            var b = pool.submit(request);
            assertEquals(
                    List.of(200, 409), java.util.stream.Stream.of(a.get(), b.get()).sorted().toList());
        }
        assertEquals(
                2,
                db.queryForObject(
                        "select label_reserved_quantity from order_items where id=?",
                        Integer.class,
                        order.item()));
        assertEquals(0, labelPosts.get());
    }

    @Test
    void labelOwnershipAndPayloadKeysAreEnforced() throws Exception {
        var order = paidOrder(2, 299900);
        UUID label = label(order, 1, 2000, "owner");
        mvc.perform(get("/api/v1/shipping-labels/" + label).with(user("seller2").roles("SELLER")))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/v1/seller-orders/" + order.group() + "/labels")
                                .with(user("seller").roles("SELLER"))
                                .with(csrf())
                                .header("Idempotency-Key", "owner")
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                Map.of("itemId", order.item(), "quantity", 2, "maxCostMinor", 2000))))
                .andExpect(status().isConflict());
    }

    @Test
    void lostTaxSaleResponseReusesProviderKey() throws Exception {
        paidOrder(2, 299900);
        UUID id = saleJob();
        loseSale.set(true);
        assertThrows(Exception.class, () -> reporting.process(id));
        assertEquals(1, remoteTax.size());
        taxDue(id);
        reporting.process(id);
        assertEquals(1, remoteTax.size());
        assertEquals(2, salePosts.get());
        assertEquals(
                "DONE",
                db.queryForObject("select status from tax_reporting_jobs where id=?", String.class, id));
        reporting.process(id);
        assertEquals(2, salePosts.get());
    }

    @Test
    void cancellationTaxReversalWaitsForSaleAndIncludesShipping() throws Exception {
        var order = paidOrder(2, 299900);
        UUID reversal = cancellation(order);
        reporting.process(reversal);
        assertEquals(0, reversalPosts.get());
        assertEquals(
                0,
                db.queryForObject(
                        "select attempts from tax_reporting_jobs where id=?", Integer.class, reversal));
        reporting.process(saleJob());
        taxDue(reversal);
        reporting.process(reversal);
        assertEquals(
                "DONE",
                db.queryForObject(
                        "select status from tax_reporting_jobs where id=?", String.class, reversal));
        assertEquals(1, reversalPosts.get());
    }

    @Test
    void taxReversalExistsOnlyAfterFinancialRefundCompletes() throws Exception {
        var order = paidOrder(2, 299900);
        reporting.process(saleJob());
        UUID shipment = shipAll(order, 2);
        receivedReturn(order, shipment, 1, "partial");
        assertEquals(
                0,
                db.queryForObject(
                        "select count(*) from tax_reporting_jobs where kind='REVERSAL'", Integer.class));
        workflow.refund(db.queryForObject("select id from refunds", UUID.class));
        UUID reversal =
                db.queryForObject("select id from tax_reporting_jobs where kind='REVERSAL'", UUID.class);
        loseReversal.set(true);
        assertThrows(Exception.class, () -> reporting.process(reversal));
        taxDue(reversal);
        reporting.process(reversal);
        assertEquals(2, remoteTax.size());
        assertEquals(2, reversalPosts.get());
        assertEquals(
                "DONE",
                db.queryForObject(
                        "select status from tax_reporting_jobs where id=?", String.class, reversal));
    }

    @Test
    void taxProviderSuccessThenDatabaseFailureUsesGetOnRecovery() throws Exception {
        paidOrder(2, 299900);
        UUID id = saleJob();
        db.execute(
                "create function fail_tax_done() returns trigger language plpgsql as $$ begin if"
                        + " NEW.status='DONE' then raise exception 'injected tax finalization crash'; end if;"
                        + " return NEW; end $$");
        db.execute(
                "create trigger fail_tax_done before update on tax_reporting_jobs for each row execute"
                        + " function fail_tax_done()");
        try {
            assertThrows(Exception.class, () -> reporting.process(id));
            assertNotNull(
                    db.queryForObject(
                            "select provider_id from tax_reporting_jobs where id=?", String.class, id));
            assertEquals(
                    "READY",
                    db.queryForObject("select status from tax_reporting_jobs where id=?", String.class, id));
        } finally {
            db.execute("drop trigger fail_tax_done on tax_reporting_jobs");
            db.execute("drop function fail_tax_done()");
        }
        taxDue(id);
        reporting.process(id);
        assertEquals(1, salePosts.get());
        assertEquals(
                "DONE",
                db.queryForObject("select status from tax_reporting_jobs where id=?", String.class, id));
    }

    @Test
    void oldUnknownTaxPostFailsClosedButKnownReferenceRecovers() throws Exception {
        paidOrder(2, 299900);
        UUID id = saleJob();
        loseSale.set(true);
        assertThrows(Exception.class, () -> reporting.process(id));
        db.update(
                "update tax_reporting_jobs set provider_started_at=now()-interval '25"
                        + " hours',next_attempt=now() where id=?",
                id);
        reporting.process(id);
        assertEquals(
                "MANUAL_REVIEW",
                db.queryForObject("select status from tax_reporting_jobs where id=?", String.class, id));
        assertEquals(1, salePosts.get());
        String providerId = remoteTax.values().iterator().next().get("id").toString();
        reporting.reconcileKnown(id, providerId);
        reporting.process(id);
        assertEquals(
                "DONE",
                db.queryForObject("select status from tax_reporting_jobs where id=?", String.class, id));
        assertEquals(1, salePosts.get());
    }

    @Test
    void repeatedTinyUnitReturnsConserveSeparateNetAndTaxReports() throws Exception {
        var order = paidOrder(10, 7);
        reporting.process(saleJob());
        UUID shipment = shipAll(order, 10);
        for (int n = 0; n < 10; n++) {
            UUID returned = receivedReturn(order, shipment, 1, "rounding-" + n);
            UUID refund =
                    db.queryForObject("select id from refunds where return_id=?", UUID.class, returned);
            workflow.refund(refund);
            UUID report =
                    db.queryForObject(
                            "select id from tax_reporting_jobs where refund_id=?", UUID.class, refund);
            reporting.process(report);
        }
        assertEquals(70, db.queryForObject("select sum(refund_net) from returns", Long.class));
        assertEquals(4, db.queryForObject("select sum(refund_tax) from returns", Long.class));
        assertEquals(
                74,
                db.queryForObject(
                        "select refunded from payments where order_id=?", Long.class, order.order()));
        assertEquals(10, reversalPosts.get());
        assertEquals(11, remoteTax.size());
    }

    @Test
    void historicalCapturedAndRefundedOrdersAreBackfilledIdempotently() throws Exception {
        var order = paidOrder(2, 299900);
        cancellation(order);
        db.update("delete from tax_reporting_jobs where kind='REVERSAL'");
        db.update("delete from tax_reporting_jobs where kind='SALE'");
        db.update("update payments set tax_reporting_checked=false");
        db.update("update refunds set tax_reporting_checked=false");
        reporting.backfill();
        reporting.backfill();
        assertEquals(2, db.queryForObject("select count(*) from tax_reporting_jobs", Integer.class));
        reporting.process(saleJob());
        UUID reverse =
                db.queryForObject("select id from tax_reporting_jobs where kind='REVERSAL'", UUID.class);
        reporting.process(reverse);
        assertEquals(
                2,
                db.queryForObject(
                        "select count(*) from tax_reporting_jobs where status='DONE'", Integer.class));
        assertEquals(1, salePosts.get());
        assertEquals(1, reversalPosts.get());
    }

    @Test
    void legacyPartialTaxRefundWithUnknownComponentsRequiresManualReview() throws Exception {
        var order = paidOrder(2, 299900);
        UUID shipment = shipAll(order, 2);
        UUID returned = receivedReturn(order, shipment, 1, "legacy-components");
        db.update("update returns set refund_net=null,refund_tax=null where id=?", returned);
        workflow.refund(
                db.queryForObject("select id from refunds where return_id=?", UUID.class, returned));
        reporting.process(saleJob());
        UUID reverse =
                db.queryForObject("select id from tax_reporting_jobs where kind='REVERSAL'", UUID.class);
        reporting.process(reverse);
        assertEquals(
                "MANUAL_REVIEW",
                db.queryForObject(
                        "select status from tax_reporting_jobs where id=?", String.class, reverse));
        assertEquals(0, reversalPosts.get());
    }

    @Test
    void purchasedLabelThenDatabaseFailureRecoversByLookupOnly() throws Exception {
        var order = paidOrder(2, 299900);
        UUID label = label(order, 1, 2000, "finalize-crash");
        db.execute(
                "create function fail_label_done() returns trigger language plpgsql as $$ begin if"
                        + " NEW.status='PURCHASED' then raise exception 'injected label commit failure'; end"
                        + " if; return NEW; end $$");
        db.execute(
                "create trigger fail_label_done before update on shipping_labels for each row execute"
                        + " function fail_label_done()");
        try {
            assertThrows(Exception.class, () -> labels.process(label));
            assertNotNull(
                    db.queryForObject(
                            "select provider_id from shipping_labels where id=?", String.class, label));
            assertEquals(
                    "ATTEMPTED",
                    db.queryForObject("select status from shipping_labels where id=?", String.class, label));
        } finally {
            db.execute("drop trigger fail_label_done on shipping_labels");
            db.execute("drop function fail_label_done()");
        }
        labelDue(label);
        labels.process(label);
        assertEquals(
                "PURCHASED",
                db.queryForObject("select status from shipping_labels where id=?", String.class, label));
        assertEquals(1, labelPosts.get());
    }

    @Test
    void overlappingLabelWorkersAfterLeaseExpiryStillIssueOnePurchase() throws Exception {
        var order = paidOrder(2, 299900);
        UUID label = label(order, 1, 2000, "lease-race");
        loseLabel.set(true);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first =
                    pool.submit(
                            () -> {
                                try {
                                    labels.process(label);
                                } catch (Exception expected) {
                                }
                                return true;
                            });
            org.awaitility.Awaitility.await()
                    .atMost(java.time.Duration.ofSeconds(3))
                    .until(() -> labelPosts.get() == 1 && !remoteLabels.isEmpty());
            labelDue(label);
            labels.process(label);
            first.get();
        }
        assertEquals(1, labelPosts.get());
        assertEquals(
                "PURCHASED",
                db.queryForObject("select status from shipping_labels where id=?", String.class, label));
    }

    @Test
    void onlyAuthoritativelyFailedOrRefundedUndispatchedLabelsReleaseUnits() throws Exception {
        var order = paidOrder(2, 299900);
        UUID label = label(order, 1, 2000, "refund-evidence");
        labels.process(label);
        mvc.perform(
                        post("/api/v1/admin/shipping-labels/" + label + "/release-closed")
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf()))
                .andExpect(status().isConflict());
        remoteLabels.values().iterator().next().put("status", "REFUNDED");
        postJson("/admin/shipping-labels/" + label + "/release-closed", Map.of(), "admin", null);
        postJson("/admin/shipping-labels/" + label + "/release-closed", Map.of(), "admin", null);
        assertEquals(
                0,
                db.queryForObject(
                        "select label_reserved_quantity from order_items where id=?",
                        Integer.class,
                        order.item()));
        assertEquals(1, labelPosts.get());
        postJson("/orders/" + order.order() + "/cancel", Map.of(), "tax-user", null);
    }

    record PurchaseOrder(UUID order, UUID item, UUID group) {
    }
}
