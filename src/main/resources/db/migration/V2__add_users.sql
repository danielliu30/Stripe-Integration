-- ============================================================================
-- Users table for authentication
-- ============================================================================

CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(30) NOT NULL,
    tenant_id BIGINT,
    pm_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_users_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_users_pm FOREIGN KEY (pm_id) REFERENCES property_managers(id),
    CONSTRAINT chk_users_role CHECK (role IN ('TENANT', 'PROPERTY_MANAGER')),
    CONSTRAINT chk_users_role_ref CHECK (
        (role = 'TENANT' AND tenant_id IS NOT NULL AND pm_id IS NULL) OR
        (role = 'PROPERTY_MANAGER' AND pm_id IS NOT NULL AND tenant_id IS NULL)
    )
);

-- ============================================================================
-- Seed users (password is 'password' for all, BCrypt hash)
-- ============================================================================

-- Property manager user
INSERT INTO users (email, password_hash, role, pm_id) VALUES
    ('admin@greenfieldproperties.com', '$2a$10$HhyB5rSv5U4achJDzeFnqOBHNeREzSR6ngeZFQbry8WzvKdjTrCKS', 'PROPERTY_MANAGER', 1);

-- Tenant users
INSERT INTO users (email, password_hash, role, tenant_id) VALUES
    ('alice.johnson@email.com', '$2a$10$HhyB5rSv5U4achJDzeFnqOBHNeREzSR6ngeZFQbry8WzvKdjTrCKS', 'TENANT', 1),
    ('bob.smith@email.com', '$2a$10$HhyB5rSv5U4achJDzeFnqOBHNeREzSR6ngeZFQbry8WzvKdjTrCKS', 'TENANT', 2),
    ('carol.williams@email.com', '$2a$10$HhyB5rSv5U4achJDzeFnqOBHNeREzSR6ngeZFQbry8WzvKdjTrCKS', 'TENANT', 3);
