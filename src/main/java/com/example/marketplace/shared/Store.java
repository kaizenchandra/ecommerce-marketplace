package com.example.marketplace.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@Component
public class Store {
    public final JdbcTemplate db;
    public final ObjectMapper json;

    public Store(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public String user() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    public void role(String role) {
        if (SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .noneMatch(a -> a.getAuthority().equals("ROLE_" + role)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Role required");
    }

    public void seller(UUID seller) {
        role("SELLER");
        if (db.queryForObject(
                "select count(*) from sellers where id=? and owner_id=? and status='ACTIVE'",
                Integer.class,
                seller,
                user())
                != 1) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Seller not active or owned");
    }

    public void customer() {
        db.update(
                "insert into customers(id,display_name) values (?,?) on conflict do nothing",
                user(),
                user());
        Rules.require(
                Boolean.TRUE.equals(
                        db.queryForObject("select active from customers where id=?", Boolean.class, user())),
                "Account deactivated");
    }

    public String json(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    public void event(UUID order, String type) {
        db.update(
                "insert into"
                        + " outbox_events(id,aggregate_id,event_type,payload,aggregate_version,correlation_id)"
                        + " select ?,?,?,?::jsonb,version,id from orders where id=?",
                UUID.randomUUID(),
                order,
                type,
                json(Map.of("orderId", order, "type", type, "schemaVersion", 1)),
                order);
    }

    public void audit(String action, Object target) {
        db.update(
                "insert into audit_log(id,actor,action,target) values (?,?,?,?)",
                UUID.randomUUID(),
                user(),
                action,
                target.toString());
    }

    public Map<String, Object> ownedOrder(UUID id) {
        return db.queryForMap("select * from orders where id=? and customer_id=?", id, user());
    }
}
