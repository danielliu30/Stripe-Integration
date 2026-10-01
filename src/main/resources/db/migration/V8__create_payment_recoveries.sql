CREATE TABLE payment_recoveries (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    payment_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    available_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claimed_at TIMESTAMP NULL,
    published_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_payment_recoveries_payment UNIQUE (payment_id),
    CONSTRAINT fk_payment_recoveries_payment FOREIGN KEY (payment_id) REFERENCES payments(id),
    CONSTRAINT chk_payment_recoveries_status CHECK (status IN
        ('PENDING', 'PUBLISHING', 'PUBLISHED', 'COMPLETED'))
);

CREATE INDEX idx_payment_recoveries_due
    ON payment_recoveries (status, available_at, id);
CREATE INDEX idx_payment_recoveries_stale_claim
    ON payment_recoveries (status, claimed_at, id);
