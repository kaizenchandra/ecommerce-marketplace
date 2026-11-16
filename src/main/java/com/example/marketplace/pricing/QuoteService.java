package com.example.marketplace.pricing;

import com.example.marketplace.delivery.ShippingProvider;
import com.example.marketplace.shared.Rules;
import com.example.marketplace.shared.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

@Service
public class QuoteService {
    private final Store s;
    private final TransactionTemplate tx;
    private final ShippingProvider shipping;
    private final TaxProvider taxes;
    private final java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(8);

    public QuoteService(
            Store s, PlatformTransactionManager manager, ShippingProvider shipping, TaxProvider taxes) {
        this.s = s;
        this.tx = new TransactionTemplate(manager);
        this.shipping = shipping;
        this.taxes = taxes;
    }

    private Plan read(UUID addressId, String code) {
        s.customer();
        s.db.update("insert into carts(customer_id) values (?) on conflict do nothing", s.user());
        s.db.queryForMap("select * from carts where customer_id=? for update", s.user());
        s.db.update(
                "delete from cart_items where customer_id=? and exists(select 1 from carts where"
                        + " customer_id=? and updated_at<now()-interval '30 days')",
                s.user(),
                s.user());
        s.db.update("update carts set updated_at=now() where customer_id=?", s.user());
        var address =
                s.db.queryForMap(
                        "select a.line1,a.city,a.postal_code,a.country,d.state from addresses a left join"
                                + " address_details d on d.address_id=a.id where a.id=? and a.customer_id=?",
                        addressId,
                        s.user());
        var items =
                s.db.queryForList(
                        "select c.offer_id,c.quantity,o.price,o.seller_id,p.id product_id,p.name"
                                + " title,v.attributes::text attributes,sp.address::text"
                                + " origin,sp.length_cm,sp.width_cm,sp.height_cm,sp.max_weight_grams,sp.version"
                                + " shipping_version from cart_items c join seller_offers o on o.id=c.offer_id join"
                                + " sellers se on se.id=o.seller_id join product_variants v on v.id=o.variant_id"
                                + " join products p on p.id=v.product_id left join seller_shipping_profiles sp on"
                                + " sp.seller_id=se.id where c.customer_id=? and o.active and se.status='ACTIVE'"
                                + " and p.status='ACTIVE' order by c.offer_id",
                        s.user());
        Rules.require(
                !items.isEmpty()
                        && items.size()
                        == s.db.queryForObject(
                        "select count(*) from cart_items where customer_id=?", Integer.class, s.user()),
                "Cart empty or unavailable");
        String coupon = code == null || code.isBlank() ? null : code;
        int percent =
                coupon == null
                        ? 0
                        : s.db.queryForObject(
                        "select percent from promotions where code=? and expires_at>now() and used<max_uses"
                                + " and (customer_id is null or customer_id=?)",
                        Integer.class,
                        coupon,
                        s.user());
        long subtotal = 0;
        for (var item : items)
            subtotal =
                    Math.addExact(
                            subtotal,
                            Math.multiplyExact(
                                    ((Number) item.get("price")).longValue(),
                                    ((Number) item.get("quantity")).intValue()));
        return new Plan(address, items, coupon, subtotal, Rules.discount(subtotal, percent));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object raw) {
        try {
            return raw == null ? Map.of() : s.json.readValue(raw.toString(), Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid provider configuration");
        }
    }

    public Object create(UUID addressId, String coupon) {
        if (!slots.tryAcquire())
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                    "Quote capacity exceeded; retry later");
        try {
            return calculate(addressId, coupon);
        } finally {
            slots.release();
        }
    }

    private Object calculate(UUID addressId, String coupon) {
        Instant budget = Instant.now().plusSeconds(15);
        Plan plan = tx.execute(t -> read(addressId, coupon));
        String fingerprint = s.json(plan);
        Map<String, Object> destination = new HashMap<>();
        destination.put("name", s.user());
        destination.put("street1", plan.address().get("line1"));
        destination.put("city", plan.address().get("city"));
        destination.put("zip", plan.address().get("postal_code"));
        destination.put("country", plan.address().get("country"));
        if (plan.address().get("state") != null) destination.put("state", plan.address().get("state"));
        long netTotal = plan.subtotal() - plan.discount(), cumulative = 0, allocated = 0;
        Map<UUID, Long> nets = new HashMap<>(), lineTaxes = new HashMap<>();
        Map<UUID, Map<String, Object>> shippingData = new HashMap<>();
        Map<UUID, List<Map<String, Object>>> groups = new TreeMap<>();
        for (var item : plan.items()) {
            long gross =
                    ((Number) item.get("price")).longValue() * ((Number) item.get("quantity")).intValue();
            cumulative += gross;
            long part = Rules.allocatedNet(cumulative, netTotal, plan.subtotal());
            nets.put((UUID) item.get("offer_id"), part - allocated);
            allocated = part;
            groups.computeIfAbsent((UUID) item.get("seller_id"), key -> new ArrayList<>()).add(item);
        }
        long shippingTotal = 0, taxTotal = 0;
        Instant expires = Instant.now().plusSeconds(300);
        Map<String, Object> references = new HashMap<>();
        // External calls are deliberately outside BOTH database transactions.
        for (var group : groups.entrySet()) {
            Rules.require(Instant.now().isBefore(budget), "Quote provider time budget exceeded");
            var first = group.getValue().getFirst();
            Map<String, Object> origin = map(first.get("origin"));
            Map<String, Object> parcel = new HashMap<>();
            long grams = 0;
            List<TaxProvider.Line> taxLines = new ArrayList<>();
            for (var item : group.getValue()) {
                var attributes = map(item.get("attributes"));
                long weight = Long.parseLong(attributes.getOrDefault("weightGrams", "0").toString());
                Rules.require(weight >= 0 && weight <= 1000000, "Invalid product weight");
                grams =
                        Math.addExact(
                                grams, Math.multiplyExact(weight, ((Number) item.get("quantity")).intValue()));
                taxLines.add(
                        new TaxProvider.Line(
                                (UUID) item.get("offer_id"),
                                nets.get(item.get("offer_id")),
                                (String) attributes.get("taxCode")));
            }
            if (!origin.isEmpty()) {
                Rules.require(
                        grams > 0 && grams <= ((Number) first.get("max_weight_grams")).longValue(),
                        "Parcel weight exceeds configured packing limits");
                parcel.put("length", first.get("length_cm").toString());
                parcel.put("width", first.get("width_cm").toString());
                parcel.put("height", first.get("height_cm").toString());
                parcel.put("distance_unit", "cm");
                parcel.put("weight", Long.toString(grams));
                parcel.put("mass_unit", "g");
            }
            for (var item : group.getValue()) {
                var attributes = map(item.get("attributes"));
                shippingData.put(
                        (UUID) item.get("offer_id"),
                        Map.of(
                                "origin",
                                origin,
                                "destination",
                                destination,
                                "parcel",
                                new HashMap<>(parcel),
                                "weightGrams",
                                attributes.getOrDefault("weightGrams", "0"),
                                "maxWeightGrams",
                                first.get("max_weight_grams") == null ? 0 : first.get("max_weight_grams")));
            }
            var rate = shipping.quote(origin, destination, parcel);
            Rules.require(rate.amount() >= 0, "Negative shipping cost");
            Rules.require(Instant.now().isBefore(budget), "Quote provider time budget exceeded");
            var tax = taxes.calculate(origin, destination, taxLines, rate.amount());
            Rules.require(
                    tax.lineTax().keySet().equals(netsFor(group.getValue()))
                            && tax.shippingTax() >= 0
                            && tax.lineTax().values().stream().allMatch(value -> value != null && value >= 0),
                    "Invalid tax allocation");
            shippingTotal = Math.addExact(shippingTotal, rate.amount());
            taxTotal = Math.addExact(taxTotal, tax.shippingTax());
            for (long value : tax.lineTax().values()) taxTotal = Math.addExact(taxTotal, value);
            lineTaxes.putAll(tax.lineTax());
            if (tax.expiresAt().isBefore(expires)) expires = tax.expiresAt();
            references.put(
                    group.getKey().toString(),
                    Map.of(
                            "shipping",
                            rate.details(),
                            "shippingRateRef",
                            rate.reference(),
                            "shippingMinor",
                            rate.amount(),
                            "taxRef",
                            tax.reference(),
                            "shippingTaxMinor",
                            tax.shippingTax(),
                            "origin",
                            origin,
                            "parcel",
                            parcel));
        }
        Rules.require(Instant.now().isBefore(budget), "Quote provider time budget exceeded");
        final long shippingAmount = shippingTotal, taxAmount = taxTotal;
        final Instant deadline = expires;
        Rules.require(deadline.isAfter(Instant.now()), "Provider quote expired");
        return tx.execute(
                t -> {
                    Plan current = read(addressId, coupon);
                    Rules.require(
                            fingerprint.equals(s.json(current)),
                            "Cart or provider configuration changed; request a new quote");
                    UUID quote = UUID.randomUUID();
                    long total = Math.addExact(netTotal, Math.addExact(shippingAmount, taxAmount));
                    s.db.update(
                            "insert into"
                                    + " checkout_quotes(id,customer_id,address,coupon,subtotal,discount,shipping,tax,total,expires_at,provider_details)"
                                    + " values (?,?,?::jsonb,?,?,?,?,?,?,?,?::jsonb)",
                            quote,
                            s.user(),
                            s.json(plan.address()),
                            plan.coupon(),
                            plan.subtotal(),
                            plan.discount(),
                            shippingAmount,
                            taxAmount,
                            total,
                            java.sql.Timestamp.from(deadline),
                            s.json(references));
                    for (var item : plan.items())
                        s.db.update(
                                "insert into"
                                        + " quote_items(id,quote_id,offer_id,seller_id,product_id,title,quantity,unit_price,net,tax,shipping_data)"
                                        + " values (?,?,?,?,?,?,?,?,?,?,?::jsonb)",
                                UUID.randomUUID(),
                                quote,
                                item.get("offer_id"),
                                item.get("seller_id"),
                                item.get("product_id"),
                                item.get("title"),
                                item.get("quantity"),
                                item.get("price"),
                                nets.get(item.get("offer_id")),
                                lineTaxes.get(item.get("offer_id")),
                                s.json(shippingData.get(item.get("offer_id"))));
                    return s.db.queryForMap("select * from checkout_quotes where id=?", quote);
                });
    }

    private Set<UUID> netsFor(List<Map<String, Object>> items) {
        Set<UUID> ids = new HashSet<>();
        items.forEach(item -> ids.add((UUID) item.get("offer_id")));
        return ids;
    }

    private record Plan(
            Map<String, Object> address,
            List<Map<String, Object>> items,
            String coupon,
            long subtotal,
            long discount) {
    }
}
