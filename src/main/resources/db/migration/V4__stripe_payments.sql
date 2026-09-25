-- ============================================================================
-- Stripe credit card payments: cards, payment lifecycle (phase 1)
-- (notifications + outbox_events land with the notification pipeline, phase 2)
-- ============================================================================

-- Saved cards (Stripe PaymentMethod mirror — PAN data lives only in Stripe)
CREATE TABLE cards (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    stripe_payment_method_id VARCHAR(255) NOT NULL,
    brand VARCHAR(50) NOT NULL,
    last4 VARCHAR(4) NOT NULL,
    exp_month INT NOT NULL,
    exp_year INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_cards_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT uk_cards_stripe_pm UNIQUE (stripe_payment_method_id)
);

CREATE INDEX idx_cards_tenant ON cards(tenant_id, id);

-- Stripe Customer linkage (lazily populated on first setup-intent)
-- Single-action ALTERs + CONSTRAINT form used throughout: Flyway runs these
-- scripts on both MySQL and H2 (tests), and H2 rejects MySQL's multi-action
-- ALTER TABLE and ADD UNIQUE KEY syntax.
ALTER TABLE tenants ADD COLUMN stripe_customer_id VARCHAR(255);
ALTER TABLE tenants ADD CONSTRAINT uk_tenants_stripe_customer UNIQUE (stripe_customer_id);

-- ============================================================================
-- Payments: lifecycle status + Stripe/card linkage
-- Existing rows are settled manual payments -> backfill as SUCCEEDED.
-- ============================================================================

ALTER TABLE payments ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'SUCCEEDED';
ALTER TABLE payments ADD COLUMN card_id BIGINT;
ALTER TABLE payments ADD COLUMN stripe_payment_intent_id VARCHAR(255);
ALTER TABLE payments ADD COLUMN idempotency_key VARCHAR(255);
ALTER TABLE payments ADD COLUMN failure_reason VARCHAR(500);
ALTER TABLE payments ADD CONSTRAINT uk_payments_stripe_pi UNIQUE (stripe_payment_intent_id);
ALTER TABLE payments ADD CONSTRAINT uk_payments_idempotency UNIQUE (idempotency_key);
ALTER TABLE payments ADD CONSTRAINT fk_payments_card FOREIGN KEY (card_id) REFERENCES cards(id);
ALTER TABLE payments ADD CONSTRAINT chk_payments_status CHECK (status IN
    ('INITIATED', 'REQUIRES_ACTION', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'REFUNDED'));

-- Extend payment_method to include CREDIT_CARD
-- (constraint kept its pre-rename name from V1; standard-SQL DROP CONSTRAINT
-- form used because jOOQ's DDL codegen parser doesn't support DROP CHECK)
ALTER TABLE payments DROP CONSTRAINT chk_manual_payments_method;
ALTER TABLE payments ADD CONSTRAINT chk_payments_method CHECK (payment_method IN
    ('CASH', 'CHECK', 'OTHER', 'CREDIT_CARD'));
