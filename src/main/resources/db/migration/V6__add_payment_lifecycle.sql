ALTER TABLE payments ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'SUCCEEDED';
ALTER TABLE payments ADD COLUMN card_id BIGINT;
ALTER TABLE payments ADD COLUMN stripe_payment_intent_id VARCHAR(255);
ALTER TABLE payments ADD COLUMN idempotency_key VARCHAR(255);
ALTER TABLE payments ADD COLUMN failure_reason VARCHAR(500);
ALTER TABLE payments ADD CONSTRAINT uq_payments_stripe_payment_intent UNIQUE (stripe_payment_intent_id);
ALTER TABLE payments ADD CONSTRAINT uq_payments_idempotency_key UNIQUE (idempotency_key);
ALTER TABLE payments ADD CONSTRAINT fk_payments_card FOREIGN KEY (card_id) REFERENCES cards(id);
ALTER TABLE payments ADD CONSTRAINT chk_payments_status CHECK (status IN
    ('INITIATED', 'REQUIRES_ACTION', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'REFUNDED'));
ALTER TABLE payments DROP CONSTRAINT chk_manual_payments_method;
ALTER TABLE payments ADD CONSTRAINT chk_payments_method CHECK (payment_method IN
    ('CASH', 'CHECK', 'OTHER', 'CREDIT_CARD'));
