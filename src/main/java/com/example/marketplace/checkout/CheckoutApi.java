package com.example.marketplace.checkout;

import com.example.marketplace.shared.Rules;
import com.example.marketplace.shared.Store;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class CheckoutApi {
    private final Store s;
    private final com.example.marketplace.pricing.QuoteService quotes;

    public CheckoutApi(Store s, com.example.marketplace.pricing.QuoteService quotes) {
        this.s = s;
        this.quotes = quotes;
    }

    private void cartLock() {
        s.customer();
        s.db.update("insert into carts(customer_id) values (?) on conflict do nothing", s.user());
        s.db.queryForMap("select * from carts where customer_id=? for update", s.user());
        s.db.update(
                "delete from cart_items where customer_id=? and exists(select 1 from carts where"
                        + " customer_id=? and updated_at<now()-interval '30 days')",
                s.user(),
                s.user());
        s.db.update("update carts set updated_at=now() where customer_id=?", s.user());
    }

    @GetMapping("/cart")
    Object cart() {
        s.customer();
        return s.db.queryForList(
                "select c.*,o.price,p.name from cart_items c join seller_offers o on o.id=c.offer_id join"
                        + " product_variants v on v.id=o.variant_id join products p on p.id=v.product_id where"
                        + " c.customer_id=? order by c.id",
                s.user());
    }

    @PostMapping("/cart/items")
    @Transactional
    Object add(@Valid @RequestBody Item item) {
        cartLock();
        Rules.require(
                s.db.queryForObject(
                        "select count(*) from cart_items where customer_id=?", Integer.class, s.user())
                        < 30,
                "Cart limit 30 offers");
        s.db.update(
                "insert into cart_items(id,customer_id,offer_id,quantity) values (?,?,?,?) on"
                        + " conflict(customer_id,offer_id) do update set quantity=excluded.quantity",
                UUID.randomUUID(),
                s.user(),
                item.offerId(),
                item.quantity());
        return cart();
    }

    @PatchMapping("/cart/items/{id}")
    @Transactional
    Object quantity(@PathVariable UUID id, @Valid @RequestBody Quantity q) {
        cartLock();
        s.db.update(
                "update cart_items set quantity=? where id=? and customer_id=?",
                q.quantity(),
                id,
                s.user());
        return cart();
    }

    @DeleteMapping("/cart/items/{id}")
    @Transactional
    Object remove(@PathVariable UUID id) {
        cartLock();
        s.db.update("delete from cart_items where id=? and customer_id=?", id, s.user());
        return cart();
    }

    @DeleteMapping("/cart")
    @Transactional
    Object clear() {
        cartLock();
        s.db.update("delete from cart_items where customer_id=?", s.user());
        return Map.of("cleared", true);
    }

    @PostMapping("/checkout/quotes")
    public Object quote(@Valid @RequestBody Quote request) {
        return quotes.create(request.addressId(), request.coupon());
    }

    @PostMapping("/checkouts")
    @Transactional
    public Object checkout(
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 100) String key,
            @Valid @RequestBody Checkout request) {
        Rules.require(key.length() > 0 && key.length() <= 100, "Invalid idempotency key");
        cartLock();
        var previous =
                s.db.queryForList(
                        "select * from orders where customer_id=? and idempotency_key=?", s.user(), key);
        if (!previous.isEmpty()) {
            Rules.require(
                    request.quoteId().equals(previous.getFirst().get("quote_id")),
                    "Key reused with different quote");
            return previous.getFirst();
        }
        var quote =
                s.db.queryForMap(
                        "select * from checkout_quotes where id=? and customer_id=? and expires_at>now() for"
                                + " update",
                        request.quoteId(),
                        s.user());
        var items =
                s.db.queryForList(
                        "select * from quote_items where quote_id=? order by offer_id", request.quoteId());
        // Canonical lock order across checkout, stock, moderation, and seller suspension.
        for (var seller :
                items.stream().map(i -> (UUID) i.get("seller_id")).distinct().sorted().toList()) {
            Rules.require(
                    "ACTIVE"
                            .equals(
                                    s.db
                                            .queryForMap("select status from sellers where id=? for share", seller)
                                            .get("status")),
                    "Seller unavailable");
        }
        for (var product :
                items.stream().map(i -> (UUID) i.get("product_id")).distinct().sorted().toList()) {
            Rules.require(
                    "ACTIVE"
                            .equals(
                                    s.db
                                            .queryForMap("select status from products where id=? for share", product)
                                            .get("status")),
                    "Product unavailable");
        }
        UUID order = UUID.randomUUID();
        s.db.update(
                "insert into orders(id,customer_id,quote_id,idempotency_key,status,total,expires_at) values"
                        + " (?,?,?,?,'PAYMENT_PENDING',?,now()+interval '15 minutes')",
                order,
                s.user(),
                request.quoteId(),
                key,
                quote.get("total"));
        if (quote.get("coupon") != null) {
            Rules.require(
                    s.db.update(
                            "update promotions set used=used+1 where code=? and used<max_uses and"
                                    + " expires_at>now() and (customer_id is null or customer_id=?)",
                            quote.get("coupon"),
                            s.user())
                            == 1,
                    "Coupon exhausted");
            s.db.update(
                    "insert into coupon_redemptions values (?,?,?)", quote.get("coupon"), s.user(), order);
        }
        Map<UUID, UUID> groups = new HashMap<>();
        for (var i : items) {
            var offer =
                    s.db.queryForMap("select * from seller_offers where id=? for share", i.get("offer_id"));
            Rules.require(
                    Boolean.TRUE.equals(offer.get("active"))
                            && offer.get("price").equals(i.get("unit_price")),
                    "Offer changed; request a new quote");
            Rules.require(
                    s.db.update(
                            "update inventory set reserved=reserved+?,version=version+1 where offer_id=? and"
                                    + " on_hand-reserved>=?",
                            i.get("quantity"),
                            i.get("offer_id"),
                            i.get("quantity"))
                            == 1,
                    "Insufficient stock");
            s.db.update(
                    "insert into inventory_reservations values (?,?,?,'ACTIVE')",
                    order,
                    i.get("offer_id"),
                    i.get("quantity"));
            UUID seller = (UUID) i.get("seller_id");
            UUID group = groups.get(seller);
            if (group == null) {
                group = UUID.randomUUID();
                groups.put(seller, group);
                s.db.update("insert into seller_orders values (?,?,?)", group, order, seller);
            }
            s.db.update(
                    "insert into"
                            + " order_items(id,order_id,seller_order_id,offer_id,product_id,title,quantity,unit_price,net,tax,shipping_data)"
                            + " values (?,?,?,?,?,?,?,?,?,?,?::jsonb)",
                    UUID.randomUUID(),
                    order,
                    group,
                    i.get("offer_id"),
                    i.get("product_id"),
                    i.get("title"),
                    i.get("quantity"),
                    i.get("unit_price"),
                    i.get("net"),
                    i.get("tax"),
                    i.get("shipping_data").toString());
        }
        s.db.update(
                "insert into payments(id,order_id,status,amount) values (?,?,'CREATED',?)",
                UUID.randomUUID(),
                order,
                quote.get("total"));
        s.db.update("insert into checkout_workflows(order_id) values (?)", order);
        s.db.update("delete from cart_items where customer_id=?", s.user());
        s.event(order, "OrderCreated");
        return s.ownedOrder(order);
    }

    @GetMapping("/orders")
    Object orders(@RequestParam(defaultValue = "0") int page) {
        Rules.require(page >= 0 && page < 10000, "Invalid page");
        return s.db.queryForList(
                "select * from orders where customer_id=? order by created_at desc limit 20 offset ?",
                s.user(),
                page * 20);
    }

    @GetMapping("/orders/{id}")
    Object order(@PathVariable UUID id) {
        return Map.of(
                "order",
                s.ownedOrder(id),
                "items",
                s.db.queryForList("select * from order_items where order_id=?", id),
                "payment",
                s.db.queryForMap("select * from payments where order_id=?", id));
    }

    public record Item(@NotNull UUID offerId, @Min(1) @Max(100) int quantity) {
    }

    public record Quantity(@Min(1) @Max(100) int quantity) {
    }

    public record Quote(@NotNull UUID addressId, @Size(max = 40) String coupon) {
    }

    public record Checkout(@NotNull UUID quoteId) {
    }
}
