ALTER TABLE tenants
    ADD COLUMN stripe_customer_id VARCHAR(255) NULL;

ALTER TABLE tenants
    ADD CONSTRAINT uq_tenants_stripe_customer_id UNIQUE (stripe_customer_id);
