-- Spring Session's PostgreSQL layout, managed by Flyway rather than startup schema mutation.
CREATE TABLE SPRING_SESSION
(
    PRIMARY_ID            CHAR(36) NOT NULL PRIMARY KEY,
    SESSION_ID            CHAR(36) NOT NULL UNIQUE,
    CREATION_TIME         BIGINT   NOT NULL,
    LAST_ACCESS_TIME      BIGINT   NOT NULL,
    MAX_INACTIVE_INTERVAL INT      NOT NULL,
    EXPIRY_TIME           BIGINT   NOT NULL,
    PRINCIPAL_NAME        VARCHAR(200)
);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);
CREATE TABLE SPRING_SESSION_ATTRIBUTES
(
    SESSION_PRIMARY_ID CHAR(36)     NOT NULL REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE,
    ATTRIBUTE_NAME     VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES    BYTEA        NOT NULL,
    PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME)
);
ALTER TABLE payments
    ADD COLUMN checkout_url varchar(2048);
-- Local operations are committed BEFORE external POSTs. Missing response references are ambiguous.
CREATE TABLE stripe_operations
(
    operation_key varchar(120) PRIMARY KEY,
    kind          varchar(20)  NOT NULL CHECK (kind IN ('CHECKOUT', 'REFUND')),
    payment_key   varchar(100) NOT NULL,
    amount        bigint       NOT NULL CHECK (amount >= 0),
    provider_id   varchar(200),
    created_at    timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX stripe_payment_key ON stripe_operations (payment_key);
CREATE TABLE provider_webhook_inbox
(
    provider     varchar(30)  NOT NULL,
    event_id     varchar(200) NOT NULL,
    order_id     uuid         NOT NULL REFERENCES orders,
    status       varchar(20)  NOT NULL DEFAULT 'READY' CHECK (status IN ('READY', 'DONE', 'MANUAL_REVIEW')),
    attempts     integer      NOT NULL DEFAULT 0,
    next_attempt timestamptz  NOT NULL DEFAULT now(),
    created_at   timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, event_id)
);
CREATE INDEX provider_webhooks_due ON provider_webhook_inbox (next_attempt) WHERE status='READY';
CREATE INDEX refunds_due ON refunds (next_attempt) WHERE status='PENDING';
ALTER TABLE payments
    ADD COLUMN reconcile_after timestamptz NOT NULL DEFAULT now();
CREATE INDEX payments_reconcile_due ON payments (reconcile_after);
