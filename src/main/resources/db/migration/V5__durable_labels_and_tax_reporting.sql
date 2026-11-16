ALTER TABLE quote_items
    ADD COLUMN shipping_data jsonb NOT NULL DEFAULT '{}';
ALTER TABLE order_items
    ADD COLUMN shipping_data jsonb NOT NULL DEFAULT '{}';
ALTER TABLE order_items
    ADD COLUMN label_reserved_quantity integer NOT NULL DEFAULT 0;
ALTER TABLE order_items
    ADD CHECK (label_reserved_quantity >= 0 AND shipped_quantity + label_reserved_quantity <= quantity);
ALTER TABLE returns
    ADD COLUMN refund_net bigint CHECK (refund_net >= 0);
ALTER TABLE returns
    ADD COLUMN refund_tax bigint CHECK (refund_tax >= 0);
UPDATE returns r
SET refund_net=r.refund_amount,
    refund_tax=0 FROM order_items i
WHERE r.order_item_id=i.id AND i.tax=0;
UPDATE returns r
SET refund_net=i.net,
    refund_tax=i.tax FROM order_items i
WHERE r.order_item_id=i.id AND r.quantity=i.quantity AND r.refund_amount=i.net+i.tax;
ALTER TABLE returns
    ADD CHECK ((refund_net IS NULL AND refund_tax IS NULL) OR
               (refund_net IS NOT NULL AND refund_tax IS NOT NULL AND refund_amount = refund_net + refund_tax));
CREATE TABLE shipping_labels
(
    id                  uuid PRIMARY KEY,
    seller_order_id     uuid         NOT NULL REFERENCES seller_orders,
    order_item_id       uuid         NOT NULL REFERENCES order_items,
    quantity            integer      NOT NULL CHECK (quantity > 0),
    idempotency_key     varchar(100) NOT NULL,
    max_cost            bigint       NOT NULL CHECK (max_cost >= 0),
    status              varchar(30)  NOT NULL DEFAULT 'READY' CHECK (status IN
                                                                     ('READY', 'ATTEMPTED', 'PURCHASED', 'DISPATCHED',
                                                                      'ABORTED', 'MANUAL_REVIEW')),
    request             jsonb        NOT NULL,
    rate_id             varchar(200),
    rate_amount         bigint CHECK (rate_amount >= 0),
    provider_id         varchar(200) UNIQUE,
    carrier             varchar(100),
    tracking            varchar(150),
    label_url           varchar(4000),
    purchase_started_at timestamptz,
    attempts            integer      NOT NULL DEFAULT 0,
    lease_token         uuid,
    next_attempt        timestamptz  NOT NULL DEFAULT now(),
    last_error          varchar(100),
    created_at          timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (seller_order_id, idempotency_key)
);
CREATE INDEX label_work_due ON shipping_labels (next_attempt) WHERE status IN ('READY','ATTEMPTED');
ALTER TABLE shipments
    ADD COLUMN label_id uuid UNIQUE REFERENCES shipping_labels;
CREATE TABLE tax_reporting_jobs
(
    id                  uuid PRIMARY KEY,
    order_id            uuid         NOT NULL REFERENCES orders,
    seller_order_id     uuid         NOT NULL REFERENCES seller_orders,
    refund_id           uuid REFERENCES refunds,
    operation_key       varchar(160) NOT NULL UNIQUE,
    kind                varchar(20)  NOT NULL CHECK (kind IN ('SALE', 'REVERSAL')),
    dependency_id       uuid REFERENCES tax_reporting_jobs,
    status              varchar(30)  NOT NULL DEFAULT 'READY' CHECK (status IN ('READY', 'DONE', 'MANUAL_REVIEW')),
    request             jsonb        NOT NULL,
    provider_id         varchar(200) UNIQUE,
    response            jsonb,
    provider_started_at timestamptz,
    attempts            integer      NOT NULL DEFAULT 0,
    next_attempt        timestamptz  NOT NULL DEFAULT now(),
    last_error          varchar(100),
    created_at          timestamptz  NOT NULL DEFAULT now(),
    CHECK ((kind = 'SALE' AND refund_id IS NULL AND dependency_id IS NULL) OR
           (kind = 'REVERSAL' AND refund_id IS NOT NULL AND dependency_id IS NOT NULL))
);
CREATE INDEX tax_jobs_due ON tax_reporting_jobs (next_attempt) WHERE status='READY';
CREATE TABLE local_label_ledger
(
    operation_id uuid PRIMARY KEY,
    provider_id  varchar(200) NOT NULL UNIQUE,
    rate_id      varchar(200) NOT NULL
);
