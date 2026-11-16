package com.example.marketplace.catalog;

import com.example.marketplace.shared.Rules;
import com.example.marketplace.shared.Store;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class CatalogAdminApi {
    private final Store s;

    public CatalogAdminApi(Store s) {
        this.s = s;
    }

    @PostMapping("/admin/products")
    @Transactional
    Object product(@Valid @RequestBody Product p) {
        UUID id = UUID.randomUUID();
        s.db.update(
                "insert into products(id,category_id,name,brand,description) values (?,?,?,?,?)",
                id,
                p.categoryId(),
                p.name(),
                p.brand(),
                p.description());
        s.audit("PRODUCT_CREATED", id);
        return Map.of("id", id);
    }

    @PostMapping("/admin/products/{id}/variants")
    @Transactional
    Object variant(@PathVariable UUID id, @Valid @RequestBody Variant v) {
        Rules.require(
                v.attributes().size() <= 30 && s.json(v.attributes()).length() < 4000,
                "Too many attributes");
        UUID variant = UUID.randomUUID();
        s.db.update(
                "insert into product_variants values (?,?,?,?::jsonb)",
                variant,
                id,
                v.sku(),
                s.json(v.attributes()));
        s.audit("VARIANT_CREATED", variant);
        return Map.of("id", variant);
    }

    @PostMapping("/admin/categories")
    @Transactional
    Object category(@Valid @RequestBody Category c) {
        s.db.update("insert into categories values (?,?)", c.id(), c.name());
        s.audit("CATEGORY_CREATED", c.id());
        return Map.of("id", c.id());
    }

    @GetMapping("/search/suggestions")
    Object suggestions(@RequestParam(defaultValue = "") String q) {
        Rules.require(q.length() >= 2 && q.length() <= 100, "Use 2 to 100 characters");
        return s.db.queryForList(
                "select distinct p.name from products p join product_variants v on v.product_id=p.id join"
                        + " seller_offers o on o.variant_id=v.id join sellers se on se.id=o.seller_id where"
                        + " p.status='ACTIVE' and o.active and se.status='ACTIVE' and"
                        + " starts_with(lower(p.name),lower(?)) order by p.name limit 10",
                q);
    }

    @GetMapping("/search/facets")
    Object facets() {
        return s.db.queryForList(
                "select p.category_id,p.brand,count(distinct p.id) products from products p join"
                        + " product_variants v on v.product_id=p.id join seller_offers o on o.variant_id=v.id"
                        + " join sellers se on se.id=o.seller_id where p.status='ACTIVE' and o.active and"
                        + " se.status='ACTIVE' group by p.category_id,p.brand order by products desc limit"
                        + " 100");
    }

    @GetMapping("/products/{id}/recommendations")
    Object recommendations(@PathVariable UUID id) {
        return s.db.queryForList(
                "select distinct p.id,p.name,p.brand from products p join product_variants v on"
                        + " v.product_id=p.id join seller_offers o on o.variant_id=v.id join sellers se on"
                        + " se.id=o.seller_id where p.category_id=(select category_id from products where id=?"
                        + " and status='ACTIVE') and p.id<>? and p.status='ACTIVE' and o.active and"
                        + " se.status='ACTIVE' order by p.name limit 8",
                id,
                id);
    }

    @PostMapping("/admin/refunds/{id}/retry")
    @Transactional
    Object retry(@PathVariable UUID id) {
        s.db.update(
                "update refunds set status='PENDING',attempts=0,next_attempt=now() where id=? and"
                        + " status='MANUAL_REVIEW'",
                id);
        s.audit("REFUND_RETRY", id);
        return Map.of("queued", true);
    }

    public record Product(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 100) String brand,
            @NotBlank @Size(max = 4000) String description,
            @NotBlank String categoryId) {
    }

    public record Variant(
            @NotBlank @Size(max = 100) String sku, @NotNull Map<String, String> attributes) {
    }

    public record Category(
            @Pattern(regexp = "[a-z0-9-]{1,40}") String id, @NotBlank @Size(max = 100) String name) {
    }
}
