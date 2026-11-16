package com.example.marketplace.catalog;

import com.example.marketplace.shared.Rules;
import com.example.marketplace.shared.Store;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class CatalogApi {
    private final Store s;
    private final CategoryCache cache;

    public CatalogApi(Store s, CategoryCache cache) {
        this.s = s;
        this.cache = cache;
    }

    @GetMapping("/csrf")
    Object csrf(HttpServletRequest r) {
        var t = (CsrfToken) r.getAttribute(CsrfToken.class.getName());
        return Map.of("token", t.getToken(), "headerName", t.getHeaderName());
    }

    @GetMapping({"/products", "/search/products"})
    Object products(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String category,
            @RequestParam(defaultValue = "") String brand,
            @RequestParam(defaultValue = "0") long min,
            @RequestParam(defaultValue = "1000000000") long max,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "relevance") String sort) {
        Rules.require(page >= 0 && page <= 10000 && q.length() <= 200, "Invalid search");
        String order =
                switch (sort) {
                    case "price" -> "price asc";
                    case "price_desc" -> "price desc";
                    default -> "rank desc,p.name";
                };
        return s.db.queryForList(
                "select p.*, min(o.price) price,ts_rank(p.search,plainto_tsquery('english',?)) rank from"
                        + " products p join product_variants v on v.product_id=p.id join seller_offers o on"
                        + " o.variant_id=v.id join sellers se on se.id=o.seller_id join inventory i on"
                        + " i.offer_id=o.id where p.status='ACTIVE' and o.active and se.status='ACTIVE' and"
                        + " i.on_hand>i.reserved and (?='' or p.search @@ plainto_tsquery('english',?)) and"
                        + " (?='' or p.category_id=?) and (?='' or p.brand=?) and o.price between ? and ? group"
                        + " by p.id order by "
                        + order
                        + " limit 24 offset ?",
                q,
                q,
                q,
                category,
                category,
                brand,
                brand,
                min,
                max,
                page * 24);
    }

    @GetMapping("/categories")
    Object categories() {
        return cache.categories();
    }

    @GetMapping("/products/{id}")
    Object product(@PathVariable UUID id) {
        return Map.of(
                "product",
                s.db.queryForMap("select * from products where id=? and status='ACTIVE'", id),
                "offers",
                s.db.queryForList(
                        "select o.*,v.sku,v.attributes,se.name seller_name,i.on_hand-i.reserved available from"
                                + " seller_offers o join product_variants v on v.id=o.variant_id join sellers se on"
                                + " se.id=o.seller_id join inventory i on i.offer_id=o.id where v.product_id=? and"
                                + " o.active and se.status='ACTIVE' order by o.price",
                        id));
    }

    @GetMapping("/offers/{id}/availability")
    Object availability(@PathVariable UUID id) {
        return s.db.queryForMap(
                "select i.on_hand-i.reserved available from inventory i join seller_offers o on"
                        + " o.id=i.offer_id join sellers se on se.id=o.seller_id join product_variants v on"
                        + " v.id=o.variant_id join products p on p.id=v.product_id where i.offer_id=? and"
                        + " o.active and se.status='ACTIVE' and p.status='ACTIVE'",
                id);
    }

    @GetMapping("/customers/me")
    Object me() {
        s.customer();
        return Map.of(
                "profile",
                s.db.queryForMap("select * from customers where id=?", s.user()),
                "roles",
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication()
                        .getAuthorities());
    }

    @PatchMapping("/customers/me")
    Object profile(@Valid @RequestBody Profile p) {
        s.customer();
        s.db.update(
                "update customers set display_name=?,active=? where id=?",
                p.displayName(),
                p.active(),
                s.user());
        return Map.of("updated", true);
    }

    @GetMapping("/customers/me/addresses")
    Object addresses() {
        s.customer();
        return s.db.queryForList("select * from addresses where customer_id=?", s.user());
    }

    @PostMapping("/customers/me/addresses")
    @Transactional
    Object address(@Valid @RequestBody Address a) {
        s.customer();
        UUID id = UUID.randomUUID();
        s.db.update(
                "insert into addresses values (?,?,?,?,?,'IN')",
                id,
                s.user(),
                a.line1(),
                a.city(),
                a.postalCode());
        if (a.state() != null)
            s.db.update("insert into address_details(address_id,state) values (?,?)", id, a.state());
        return Map.of("id", id);
    }

    @PostMapping("/sellers")
    Object register(@Valid @RequestBody Seller r) {
        s.role("SELLER");
        UUID id = UUID.randomUUID();
        s.db.update(
                "insert into sellers(id,owner_id,name,status) values (?,?,?,'PENDING_VERIFICATION')",
                id,
                s.user(),
                r.name());
        return Map.of("id", id);
    }

    @GetMapping("/sellers/{id}")
    Object seller(@PathVariable UUID id) {
        return s.db.queryForMap("select id,name,status from sellers where id=?", id);
    }

    @GetMapping("/seller/me")
    Object sellerMe() {
        s.role("SELLER");
        return s.db.queryForMap("select * from sellers where owner_id=?", s.user());
    }

    @GetMapping("/seller/offers")
    Object sellerOffers() {
        s.role("SELLER");
        return s.db.queryForList(
                "select o.*,i.on_hand,i.reserved,v.sku from seller_offers o join sellers se on"
                        + " se.id=o.seller_id join inventory i on i.offer_id=o.id join product_variants v on"
                        + " v.id=o.variant_id where se.owner_id=? order by o.id limit 200",
                s.user());
    }

    @PostMapping("/sellers/{id}/offers")
    @Transactional
    Object offer(@PathVariable UUID id, @Valid @RequestBody Offer r) {
        s.seller(id);
        UUID offer = UUID.randomUUID();
        s.db.update(
                "insert into seller_offers(id,variant_id,seller_id,price) values (?,?,?,?)",
                offer,
                r.variantId(),
                id,
                Rules.minor(r.price()));
        s.db.update("insert into inventory(offer_id,on_hand) values (?,0)", offer);
        return Map.of("id", offer);
    }

    @PatchMapping("/sellers/{id}/offers/{offer}")
    @Transactional
    Object price(@PathVariable UUID id, @PathVariable UUID offer, @Valid @RequestBody Offer r) {
        s.seller(id);
        Rules.require(
                s.db.update(
                        "update seller_offers set price=?,version=version+1 where id=? and seller_id=?",
                        Rules.minor(r.price()),
                        offer,
                        id)
                        == 1,
                "Offer missing");
        return Map.of("updated", true);
    }

    @PostMapping("/sellers/{id}/inventory/adjustments")
    @Transactional
    Object adjust(@PathVariable UUID id, @Valid @RequestBody Adjustment a) {
        s.seller(id);
        Rules.require(
                s.db.update(
                        "update inventory set on_hand=on_hand+?,version=version+1 where offer_id=? and"
                                + " on_hand+? >= reserved and exists(select 1 from seller_offers where"
                                + " id=offer_id and seller_id=?)",
                        a.delta(),
                        a.offerId(),
                        a.delta(),
                        id)
                        == 1,
                "Adjustment would invalidate stock");
        s.db.update(
                "insert into inventory_movements(id,offer_id,delta,reason) values (?,?,?,?)",
                UUID.randomUUID(),
                a.offerId(),
                a.delta(),
                a.reason());
        return Map.of("updated", true);
    }

    @GetMapping("/admin/sellers")
    Object adminSellers() {
        return s.db.queryForList("select * from sellers order by created_at desc limit 200");
    }

    @PatchMapping("/admin/sellers/{id}")
    @Transactional
    Object sellerState(@PathVariable UUID id, @RequestBody State state) {
        Rules.require(
                Set.of("ACTIVE", "SUSPENDED", "REJECTED", "CLOSED").contains(state.status()),
                "Invalid state");
        s.db.update("update sellers set status=? where id=?", state.status(), id);
        s.audit("SELLER_" + state.status(), id);
        return Map.of("updated", true);
    }

    @PatchMapping("/admin/products/{id}")
    @Transactional
    Object productState(@PathVariable UUID id, @RequestBody State state) {
        Rules.require(Set.of("ACTIVE", "HIDDEN").contains(state.status()), "Invalid state");
        s.db.update("update products set status=? where id=?", state.status(), id);
        s.audit("PRODUCT_" + state.status(), id);
        return Map.of("updated", true);
    }

    @PostMapping("/products/{id}/reviews")
    @Transactional
    Object review(@PathVariable UUID id, @Valid @RequestBody Review r) {
        s.customer();
        Rules.require(
                s.db.queryForObject(
                        "select count(*) from order_items i join orders o on o.id=i.order_id join shipments"
                                + " sh on sh.order_item_id=i.id where o.customer_id=? and i.product_id=? and"
                                + " sh.status='DELIVERED'",
                        Integer.class,
                        s.user(),
                        id)
                        > 0,
                "Delivered purchase required");
        s.db.update(
                "insert into reviews(id,product_id,customer_id,stars,body) values (?,?,?,?,?) on"
                        + " conflict(product_id,customer_id) do update set"
                        + " stars=excluded.stars,body=excluded.body,visible=true",
                UUID.randomUUID(),
                id,
                s.user(),
                r.stars(),
                r.body());
        return Map.of("updated", true);
    }

    @GetMapping("/products/{id}/reviews")
    Object reviews(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page) {
        Rules.require(page >= 0 && page < 10000, "Invalid page");
        return Map.of(
                "reviews",
                s.db.queryForList(
                        "select id,stars,body from reviews where product_id=? and visible order by id limit 20"
                                + " offset ?",
                        id,
                        page * 20),
                "rating",
                s.db.queryForMap(
                        "select count(*) count,coalesce(avg(stars),0) average from reviews where product_id=?"
                                + " and visible",
                        id));
    }

    @PatchMapping("/admin/reviews/{id}")
    Object moderate(@PathVariable UUID id) {
        s.db.update("update reviews set visible=false where id=?", id);
        s.audit("REVIEW_HIDDEN", id);
        return Map.of("updated", true);
    }

    @GetMapping("/notifications")
    Object notifications() {
        return s.db.queryForList(
                "select * from notifications where customer_id=? order by created_at desc limit 100",
                s.user());
    }

    @GetMapping("/admin/status")
    Object status() {
        return Map.of(
                "workflows",
                s.db.queryForList("select * from checkout_workflows order by updated_at desc limit 100"),
                "refunds",
                s.db.queryForList("select * from refunds order by next_attempt limit 100"),
                "paymentEvents",
                s.db.queryForList(
                        "select * from provider_webhook_inbox order by created_at desc limit 100"),
                "outboxPending",
                s.db.queryForObject(
                        "select count(*) from outbox_events where published_at is null", Long.class));
    }

    public record Profile(@NotBlank @Size(max = 120) String displayName, boolean active) {
    }

    public record Address(
            @NotBlank @Size(max = 240) String line1,
            @NotBlank @Size(max = 100) String city,
            @Pattern(regexp = "[0-9]{6}") String postalCode,
            @Pattern(regexp = "[A-Z]{2,3}") String state) {
    }

    public record Seller(@NotBlank @Size(max = 120) String name) {
    }

    public record Offer(@NotNull UUID variantId, @NotNull BigDecimal price) {
    }

    public record Adjustment(
            @NotNull UUID offerId,
            @Min(-1000000) @Max(1000000) int delta,
            @NotBlank @Size(max = 150) String reason) {
    }

    public record State(@NotBlank String status) {
    }

    public record Review(@Min(1) @Max(5) int stars, @NotBlank @Size(max = 2000) String body) {
    }
}
