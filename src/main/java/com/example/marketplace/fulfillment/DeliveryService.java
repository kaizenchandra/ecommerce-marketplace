package com.example.marketplace.fulfillment;

import com.example.marketplace.shared.Rules;
import com.example.marketplace.shared.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class DeliveryService {
    private final Store s;

    public DeliveryService(Store s) {
        this.s = s;
    }

    @Transactional
    public void delivered(UUID shipment, Instant at) {
        var original =
                s.db.queryForMap(
                        "select sh.*,so.order_id from shipments sh join seller_orders so on"
                                + " so.id=sh.seller_order_id where sh.id=?",
                        shipment);
        UUID order = (UUID) original.get("order_id");
        s.db.queryForMap("select * from orders where id=? for update", order);
        var sh = s.db.queryForMap("select * from shipments where id=? for update", shipment);
        if ("DELIVERED".equals(sh.get("status"))) return;
        Rules.require(
                at != null && !at.isAfter(Instant.now().plusSeconds(300)), "Invalid carrier delivery date");
        s.db.update(
                "update shipments set status='DELIVERED',delivered_at=?,tracking_state='DONE' where id=?",
                java.sql.Timestamp.from(at),
                shipment);
        s.db.update(
                "update order_items set delivered_quantity=delivered_quantity+? where id=?",
                sh.get("quantity"),
                sh.get("order_item_id"));
        boolean all =
                s.db.queryForObject(
                        "select count(*) from order_items where order_id=? and delivered_quantity<quantity",
                        Integer.class,
                        order)
                        == 0;
        s.db.update(
                "update orders set status=?,version=version+1 where id=?",
                all ? "FULFILLED" : "PARTIALLY_FULFILLED",
                order);
        s.event(order, "ShipmentDelivered");
    }
}
