CREATE TABLE customers
(
    id           varchar(200) PRIMARY KEY,
    display_name varchar(120) NOT NULL,
    active       boolean      NOT NULL DEFAULT true,
    preferences  jsonb        NOT NULL DEFAULT '{}'
);
CREATE TABLE addresses
(
    id          uuid PRIMARY KEY,
    customer_id varchar(200) NOT NULL REFERENCES customers,
    line1       varchar(240) NOT NULL,
    city        varchar(100) NOT NULL,
    postal_code varchar(20)  NOT NULL,
    country     char(2)      NOT NULL CHECK (country = 'IN')
);
CREATE TABLE sellers
(
    id         uuid PRIMARY KEY,
    owner_id   varchar(200) NOT NULL UNIQUE,
    name       varchar(120) NOT NULL,
    status     varchar(30)  NOT NULL CHECK (status IN
                                            ('PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED', 'REJECTED', 'CLOSED')),
    created_at timestamptz  NOT NULL DEFAULT now()
);
CREATE TABLE categories
(
    id   varchar(40) PRIMARY KEY,
    name varchar(100) NOT NULL
);
CREATE TABLE products
(
    id          uuid PRIMARY KEY,
    category_id varchar(40)   NOT NULL REFERENCES categories,
    name        varchar(200)  NOT NULL,
    brand       varchar(100)  NOT NULL,
    description varchar(4000) NOT NULL,
    status      varchar(20)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'HIDDEN')),
    image_url   varchar(500),
    search      tsvector GENERATED ALWAYS AS (to_tsvector('english', name || ' ' || brand || ' ' || description)) STORED
);
CREATE INDEX product_search ON products USING gin(search);
CREATE TABLE product_variants
(
    id         uuid PRIMARY KEY,
    product_id uuid         NOT NULL REFERENCES products,
    sku        varchar(100) NOT NULL UNIQUE,
    attributes jsonb        NOT NULL DEFAULT '{}'
);
CREATE TABLE seller_offers
(
    id         uuid PRIMARY KEY,
    variant_id uuid    NOT NULL REFERENCES product_variants,
    seller_id  uuid    NOT NULL REFERENCES sellers,
    price      bigint  NOT NULL CHECK (price > 0 AND price <= 1000000000),
    currency   char(3) NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
    active     boolean NOT NULL DEFAULT true,
    version    bigint  NOT NULL DEFAULT 0,
    UNIQUE (variant_id, seller_id)
);
CREATE INDEX offers_product ON seller_offers (variant_id);
CREATE TABLE inventory
(
    offer_id  uuid PRIMARY KEY REFERENCES seller_offers,
    warehouse varchar(50) NOT NULL DEFAULT 'default',
    on_hand   integer     NOT NULL CHECK (on_hand >= 0),
    reserved  integer     NOT NULL DEFAULT 0 CHECK (reserved >= 0 AND reserved <= on_hand),
    version   bigint      NOT NULL DEFAULT 0
);
CREATE TABLE inventory_movements
(
    id         uuid PRIMARY KEY,
    offer_id   uuid         NOT NULL REFERENCES inventory,
    delta      integer      NOT NULL,
    reason     varchar(150) NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now()
);
CREATE TABLE carts
(
    customer_id varchar(200) PRIMARY KEY REFERENCES customers,
    updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE cart_items
(
    id          uuid PRIMARY KEY,
    customer_id varchar(200) NOT NULL REFERENCES carts,
    offer_id    uuid         NOT NULL REFERENCES seller_offers,
    quantity    integer      NOT NULL CHECK (quantity BETWEEN 1 AND 100),
    UNIQUE (customer_id, offer_id)
);
CREATE TABLE promotions
(
    code        varchar(40) PRIMARY KEY,
    percent     integer     NOT NULL CHECK (percent BETWEEN 0 AND 100),
    max_uses    integer     NOT NULL CHECK (max_uses > 0),
    used        integer     NOT NULL DEFAULT 0 CHECK (used BETWEEN 0 AND max_uses),
    expires_at  timestamptz NOT NULL,
    customer_id varchar(200)
);
CREATE TABLE checkout_quotes
(
    id          uuid PRIMARY KEY,
    customer_id varchar(200) NOT NULL REFERENCES customers,
    address     jsonb        NOT NULL,
    coupon      varchar(40) REFERENCES promotions,
    subtotal    bigint       NOT NULL CHECK (subtotal >= 0),
    discount    bigint       NOT NULL CHECK (discount >= 0 AND discount <= subtotal),
    shipping    bigint       NOT NULL DEFAULT 0 CHECK (shipping >= 0),
    tax         bigint       NOT NULL DEFAULT 0 CHECK (tax >= 0),
    total       bigint       NOT NULL CHECK (total = subtotal - discount + shipping + tax),
    currency    char(3)      NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
    expires_at  timestamptz  NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now()
);
CREATE TABLE quote_items
(
    id         uuid PRIMARY KEY,
    quote_id   uuid         NOT NULL REFERENCES checkout_quotes,
    offer_id   uuid         NOT NULL REFERENCES seller_offers,
    seller_id  uuid         NOT NULL REFERENCES sellers,
    product_id uuid         NOT NULL REFERENCES products,
    title      varchar(300) NOT NULL,
    quantity   integer      NOT NULL CHECK (quantity BETWEEN 1 AND 100),
    unit_price bigint       NOT NULL CHECK (unit_price > 0),
    net        bigint       NOT NULL CHECK (net >= 0),
    UNIQUE (quote_id, offer_id)
);
CREATE TABLE orders
(
    id              uuid PRIMARY KEY,
    customer_id     varchar(200) NOT NULL REFERENCES customers,
    quote_id        uuid         NOT NULL UNIQUE REFERENCES checkout_quotes,
    idempotency_key varchar(100) NOT NULL,
    status          varchar(30)  NOT NULL CHECK (status IN
                                                 ('PAYMENT_PENDING', 'CONFIRMED', 'PARTIALLY_FULFILLED', 'FULFILLED',
                                                  'CANCELLED', 'MANUAL_REVIEW')),
    total           bigint       NOT NULL CHECK (total >= 0),
    currency        char(3)      NOT NULL DEFAULT 'INR',
    expires_at      timestamptz  NOT NULL,
    version         bigint       NOT NULL DEFAULT 0,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (customer_id, idempotency_key)
);
CREATE INDEX orders_customer ON orders (customer_id, created_at DESC);
CREATE TABLE seller_orders
(
    id        uuid PRIMARY KEY,
    order_id  uuid NOT NULL REFERENCES orders,
    seller_id uuid NOT NULL REFERENCES sellers,
    UNIQUE (order_id, seller_id)
);
CREATE TABLE order_items
(
    id              uuid PRIMARY KEY,
    order_id        uuid         NOT NULL REFERENCES orders,
    seller_order_id uuid         NOT NULL REFERENCES seller_orders,
    offer_id        uuid         NOT NULL REFERENCES seller_offers,
    product_id      uuid         NOT NULL REFERENCES products,
    title           varchar(300) NOT NULL,
    quantity        integer      NOT NULL CHECK (quantity > 0),
    unit_price      bigint       NOT NULL CHECK (unit_price > 0),
    net             bigint       NOT NULL CHECK (net >= 0)
);
CREATE TABLE inventory_reservations
(
    order_id uuid        NOT NULL REFERENCES orders,
    offer_id uuid        NOT NULL REFERENCES inventory,
    quantity integer     NOT NULL CHECK (quantity > 0),
    status   varchar(20) NOT NULL CHECK (status IN ('ACTIVE', 'COMMITTED', 'RELEASED')),
    PRIMARY KEY (order_id, offer_id)
);
CREATE TABLE coupon_redemptions
(
    code        varchar(40)  NOT NULL REFERENCES promotions,
    customer_id varchar(200) NOT NULL REFERENCES customers,
    order_id    uuid         NOT NULL UNIQUE REFERENCES orders,
    PRIMARY KEY (code, customer_id)
);
CREATE TABLE payments
(
    id           uuid PRIMARY KEY,
    order_id     uuid        NOT NULL UNIQUE REFERENCES orders,
    status       varchar(30) NOT NULL CHECK (status IN
                                             ('CREATED', 'CAPTURED', 'FAILED', 'PARTIALLY_REFUNDED', 'REFUNDED')),
    amount       bigint      NOT NULL CHECK (amount >= 0),
    refunded     bigint      NOT NULL DEFAULT 0 CHECK (refunded BETWEEN 0 AND amount),
    provider_ref varchar(100),
    version      bigint      NOT NULL DEFAULT 0
);
CREATE TABLE payment_attempts
(
    id           uuid PRIMARY KEY,
    payment_id   uuid        NOT NULL REFERENCES payments,
    operation    varchar(20) NOT NULL,
    provider_ref varchar(100),
    outcome      varchar(30) NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);
-- Deliberately separate provider ledger: simulator calls commit independently from the checkout workflow.
CREATE TABLE provider_payments
(
    operation_key varchar(100) PRIMARY KEY,
    amount        bigint      NOT NULL CHECK (amount >= 0),
    refunded      bigint      NOT NULL DEFAULT 0 CHECK (refunded BETWEEN 0 AND amount),
    status        varchar(20) NOT NULL,
    reference     uuid        NOT NULL UNIQUE,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE provider_refunds
(
    operation_key varchar(100) PRIMARY KEY,
    payment_key   varchar(100) NOT NULL REFERENCES provider_payments,
    amount        bigint       NOT NULL CHECK (amount >= 0),
    reference     uuid         NOT NULL UNIQUE
);
CREATE TABLE payment_webhook_events
(
    event_id   varchar(100) PRIMARY KEY,
    payment_id uuid        NOT NULL REFERENCES payments,
    status     varchar(30) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE checkout_workflows
(
    order_id     uuid PRIMARY KEY REFERENCES orders,
    status       varchar(20) NOT NULL DEFAULT 'READY' CHECK (status IN ('READY', 'DONE', 'MANUAL_REVIEW')),
    attempts     integer     NOT NULL DEFAULT 0,
    next_attempt timestamptz NOT NULL DEFAULT now(),
    last_error   varchar(100),
    updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX workflows_due ON checkout_workflows (next_attempt) WHERE status='READY';
CREATE TABLE shipments
(
    id              uuid PRIMARY KEY,
    seller_order_id uuid         NOT NULL REFERENCES seller_orders,
    order_item_id   uuid         NOT NULL UNIQUE REFERENCES order_items,
    carrier         varchar(100) NOT NULL,
    tracking        varchar(150) NOT NULL,
    status          varchar(20)  NOT NULL CHECK (status IN ('SHIPPED', 'DELIVERED')),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    delivered_at    timestamptz
);
CREATE TABLE returns
(
    id            uuid PRIMARY KEY,
    order_id      uuid         NOT NULL REFERENCES orders,
    order_item_id uuid         NOT NULL UNIQUE REFERENCES order_items,
    reason        varchar(500) NOT NULL,
    status        varchar(30)  NOT NULL CHECK (status IN ('REQUESTED', 'RECEIVED', 'REJECTED', 'REFUNDED')),
    created_at    timestamptz  NOT NULL DEFAULT now()
);
CREATE TABLE refunds
(
    id            uuid PRIMARY KEY,
    payment_id    uuid         NOT NULL REFERENCES payments,
    return_id     uuid UNIQUE REFERENCES returns,
    operation_key varchar(100) NOT NULL UNIQUE,
    amount        bigint       NOT NULL CHECK (amount >= 0),
    status        varchar(20)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'COMPLETED', 'MANUAL_REVIEW')),
    attempts      integer      NOT NULL DEFAULT 0,
    next_attempt  timestamptz  NOT NULL DEFAULT now(),
    provider_ref  varchar(100)
);
CREATE TABLE reviews
(
    id          uuid PRIMARY KEY,
    product_id  uuid          NOT NULL REFERENCES products,
    customer_id varchar(200)  NOT NULL REFERENCES customers,
    stars       integer       NOT NULL CHECK (stars BETWEEN 1 AND 5),
    body        varchar(2000) NOT NULL,
    visible     boolean       NOT NULL DEFAULT true,
    UNIQUE (product_id, customer_id)
);
CREATE TABLE outbox_events
(
    id             uuid PRIMARY KEY,
    aggregate_id   uuid         NOT NULL,
    event_type     varchar(100) NOT NULL,
    schema_version integer      NOT NULL DEFAULT 1,
    payload        jsonb        NOT NULL,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    published_at   timestamptz
);
CREATE INDEX outbox_pending ON outbox_events (created_at) WHERE published_at IS NULL;
CREATE TABLE processed_events
(
    event_id     uuid         NOT NULL,
    consumer     varchar(100) NOT NULL,
    processed_at timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);
CREATE TABLE notifications
(
    event_id    uuid PRIMARY KEY,
    customer_id varchar(200) NOT NULL REFERENCES customers,
    message     varchar(300) NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now()
);
CREATE TABLE audit_log
(
    id         uuid PRIMARY KEY,
    actor      varchar(200) NOT NULL,
    action     varchar(100) NOT NULL,
    target     varchar(200) NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now()
);
