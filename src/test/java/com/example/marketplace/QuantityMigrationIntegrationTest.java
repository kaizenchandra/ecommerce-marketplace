package com.example.marketplace;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("integration")
@Testcontainers
class QuantityMigrationIntegrationTest {
    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void existingWholeLineDeliveryAndRefundMigrateWithoutRepricing() {
        Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .target("3")
                .load()
                .migrate();
        var db =
                new JdbcTemplate(
                        new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
        UUID quote = UUID.randomUUID(),
                order = UUID.randomUUID(),
                group = UUID.randomUUID(),
                item = UUID.randomUUID(),
                shipment = UUID.randomUUID(),
                returned = UUID.randomUUID(),
                payment = UUID.randomUUID();
        db.update("insert into customers(id,display_name) values ('legacy','Legacy')");
        db.update(
                "insert into checkout_quotes(id,customer_id,address,subtotal,discount,total,expires_at)"
                        + " values (?,'legacy','{}',300,0,300,now()+interval '5 minutes')",
                quote);
        db.update(
                "insert into orders(id,customer_id,quote_id,idempotency_key,status,total,expires_at) values"
                        + " (?,'legacy',?,'original','FULFILLED',300,now())",
                order,
                quote);
        db.update(
                "insert into seller_orders values (?,?,'30000000-0000-0000-0000-000000000001')",
                group,
                order);
        db.update(
                "insert into order_items values"
                        + " (?,?,?,'40000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001','Historical"
                        + " snapshot',3,100,300)",
                item,
                order,
                group);
        db.update(
                "insert into"
                        + " shipments(id,seller_order_id,order_item_id,carrier,tracking,status,delivered_at)"
                        + " values (?,?,?,'Local','LEGACY','DELIVERED',now())",
                shipment,
                group,
                item);
        db.update(
                "insert into returns(id,order_id,order_item_id,reason,status) values (?,?,?,'Legacy full"
                        + " return','REFUNDED')",
                returned,
                order,
                item);
        db.update(
                "insert into payments(id,order_id,status,amount,refunded) values (?,?,'REFUNDED',300,300)",
                payment,
                order);
        db.update(
                "insert into refunds(id,payment_id,return_id,operation_key,amount,status) values"
                        + " (?,?,?,'legacy-refund',300,'COMPLETED')",
                UUID.randomUUID(),
                payment,
                returned);
        db.update(
                "insert into outbox_events(id,aggregate_id,event_type,payload) values"
                        + " (?,?,'RefundCompleted','{}')",
                UUID.randomUUID(),
                order);
        Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .load()
                .migrate();
        var line = db.queryForMap("select * from order_items where id=?", item);
        assertEquals(3, line.get("shipped_quantity"));
        assertEquals(3, line.get("delivered_quantity"));
        assertEquals(3, line.get("returned_quantity"));
        assertEquals(300L, line.get("net"));
        assertEquals(0L, line.get("tax"));
        assertEquals(
                3, db.queryForObject("select quantity from shipments where id=?", Integer.class, shipment));
        assertEquals(
                300L,
                db.queryForObject("select refund_amount from returns where id=?", Long.class, returned));
        assertEquals(
                shipment,
                db.queryForObject("select shipment_id from returns where id=?", UUID.class, returned));
        assertEquals(order, db.queryForObject("select correlation_id from outbox_events", UUID.class));
        assertEquals("COMPLETED", db.queryForObject("select status from refunds", String.class));
    }
}
