ALTER TABLE payments
    ADD COLUMN tax_reporting_checked boolean NOT NULL DEFAULT false;
ALTER TABLE refunds
    ADD COLUMN tax_reporting_checked boolean NOT NULL DEFAULT false;
CREATE INDEX tax_sale_backfill ON payments (order_id) WHERE NOT tax_reporting_checked AND status IN ('CAPTURED','PARTIALLY_REFUNDED','REFUNDED');
CREATE INDEX tax_reversal_backfill ON refunds (payment_id) WHERE NOT tax_reporting_checked AND status='COMPLETED';
