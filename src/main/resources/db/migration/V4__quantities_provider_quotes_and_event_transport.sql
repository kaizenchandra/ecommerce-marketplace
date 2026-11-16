ALTER TABLE shipments DROP CONSTRAINT shipments_order_item_id_key;
ALTER TABLE shipments
    ADD COLUMN quantity integer;
UPDATE shipments sh
SET quantity=i.quantity FROM order_items i
WHERE i.id=sh.order_item_id;
ALTER TABLE shipments
    ALTER COLUMN quantity SET NOT NULL;
ALTER TABLE shipments
    ADD CHECK (quantity > 0);
ALTER TABLE shipments
    ADD COLUMN idempotency_key varchar(100);
UPDATE shipments
SET idempotency_key='legacy:' || order_item_id;
ALTER TABLE shipments
    ALTER COLUMN idempotency_key SET NOT NULL;
ALTER TABLE shipments
    ADD UNIQUE (seller_order_id, idempotency_key);
ALTER TABLE shipments
    ADD COLUMN tracking_attempts integer NOT NULL DEFAULT 0;
ALTER TABLE shipments
    ADD COLUMN tracking_next_attempt timestamptz NOT NULL DEFAULT now();
ALTER TABLE shipments
    ADD COLUMN tracking_state varchar(20) NOT NULL DEFAULT 'READY' CHECK (tracking_state IN ('READY', 'DONE', 'MANUAL_REVIEW'));
UPDATE shipments
SET tracking_state='DONE'
WHERE status = 'DELIVERED';
ALTER TABLE returns DROP CONSTRAINT returns_order_item_id_key;
ALTER TABLE returns
    ADD COLUMN shipment_id uuid REFERENCES shipments;
UPDATE returns r
SET shipment_id=sh.id FROM shipments sh
WHERE sh.order_item_id=r.order_item_id;
ALTER TABLE returns
    ADD COLUMN quantity integer;
UPDATE returns r
SET quantity=i.quantity FROM order_items i
WHERE i.id=r.order_item_id;
ALTER TABLE returns
    ALTER COLUMN quantity SET NOT NULL;
ALTER TABLE returns
    ADD CHECK (quantity > 0);
ALTER TABLE returns
    ADD COLUMN idempotency_key varchar(100);
UPDATE returns
SET idempotency_key='legacy:' || order_item_id;
ALTER TABLE returns
    ALTER COLUMN idempotency_key SET NOT NULL;
ALTER TABLE returns
    ADD UNIQUE (order_id, idempotency_key);
ALTER TABLE returns
    ADD COLUMN refund_amount bigint NOT NULL DEFAULT 0 CHECK (refund_amount >= 0);
UPDATE returns r
SET refund_amount=i.net FROM order_items i
WHERE i.id=r.order_item_id;
ALTER TABLE order_items
    ADD COLUMN shipped_quantity integer NOT NULL DEFAULT 0;
ALTER TABLE order_items
    ADD COLUMN delivered_quantity integer NOT NULL DEFAULT 0;
ALTER TABLE order_items
    ADD COLUMN returned_quantity integer NOT NULL DEFAULT 0;
UPDATE order_items i
SET shipped_quantity=coalesce((select sum(quantity) from shipments where order_item_id = i.id), 0),
    delivered_quantity=coalesce(
            (select sum(quantity) from shipments where order_item_id = i.id and status = 'DELIVERED'), 0),
    returned_quantity=coalesce((select sum(quantity) from returns where order_item_id = i.id), 0);
ALTER TABLE order_items
    ADD CHECK (0 <= returned_quantity AND returned_quantity <= delivered_quantity AND
               delivered_quantity <= shipped_quantity AND shipped_quantity <= quantity);
ALTER TABLE quote_items
    ADD COLUMN tax bigint NOT NULL DEFAULT 0 CHECK (tax >= 0);
ALTER TABLE order_items
    ADD COLUMN tax bigint NOT NULL DEFAULT 0 CHECK (tax >= 0);
ALTER TABLE checkout_quotes
    ADD COLUMN provider_details jsonb NOT NULL DEFAULT '{}';
CREATE TABLE seller_shipping_profiles
(
    seller_id        uuid PRIMARY KEY REFERENCES sellers,
    address          jsonb   NOT NULL,
    length_cm        integer NOT NULL CHECK (length_cm > 0),
    width_cm         integer NOT NULL CHECK (width_cm > 0),
    height_cm        integer NOT NULL CHECK (height_cm > 0),
    max_weight_grams integer NOT NULL CHECK (max_weight_grams > 0),
    version          bigint  NOT NULL DEFAULT 0
);
ALTER TABLE outbox_events
    ADD COLUMN aggregate_version bigint NOT NULL DEFAULT 0;
ALTER TABLE outbox_events
    ADD COLUMN correlation_id uuid;
UPDATE outbox_events
SET correlation_id=aggregate_id;
ALTER TABLE outbox_events
    ALTER COLUMN correlation_id SET NOT NULL;
ALTER TABLE outbox_events
    ADD COLUMN attempts integer NOT NULL DEFAULT 0;
ALTER TABLE outbox_events
    ADD COLUMN next_attempt timestamptz NOT NULL DEFAULT now();
ALTER TABLE outbox_events
    ADD COLUMN delivery_state varchar(20) NOT NULL DEFAULT 'READY' CHECK (delivery_state IN ('READY', 'DONE', 'MANUAL_REVIEW'));
UPDATE outbox_events
SET delivery_state='DONE'
WHERE published_at IS NOT NULL;
CREATE INDEX outbox_due ON outbox_events (next_attempt) WHERE delivery_state='READY';
CREATE TABLE event_failures
(
    id           uuid PRIMARY KEY,
    topic        varchar(200) NOT NULL,
    partition_id integer      NOT NULL,
    offset_id    bigint       NOT NULL,
    payload      text         NOT NULL,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (topic, partition_id, offset_id)
);
CREATE TABLE consumer_versions
(
    consumer     varchar(100) NOT NULL,
    aggregate_id uuid         NOT NULL,
    version      bigint       NOT NULL,
    PRIMARY KEY (consumer, aggregate_id)
);
CREATE INDEX shipments_tracking_due ON shipments (tracking_next_attempt) WHERE status='SHIPPED' AND tracking_state='READY';
CREATE TABLE address_details
(
    address_id uuid PRIMARY KEY REFERENCES addresses ON DELETE CASCADE,
    state      varchar(10) NOT NULL
);
