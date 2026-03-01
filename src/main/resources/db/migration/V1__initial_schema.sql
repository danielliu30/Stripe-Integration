-- ============================================================================
-- Initial schema for property management platform
-- ============================================================================

CREATE TABLE property_managers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE properties (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    pm_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    address VARCHAR(500) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_properties_pm FOREIGN KEY (pm_id) REFERENCES property_managers(id)
);

CREATE TABLE units (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    property_id BIGINT NOT NULL,
    unit_number VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_units_property FOREIGN KEY (property_id) REFERENCES properties(id),
    UNIQUE KEY uk_units_property_number (property_id, unit_number)
);

CREATE TABLE tenants (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    first_name VARCHAR(255) NOT NULL,
    last_name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    phone VARCHAR(50),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE leases (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    unit_id BIGINT NOT NULL,
    rent_amount DECIMAL(10, 2) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_leases_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_leases_unit FOREIGN KEY (unit_id) REFERENCES units(id),
    CONSTRAINT chk_leases_status CHECK (status IN ('DRAFT', 'ACTIVE', 'EXPIRED'))
);

CREATE TABLE rent_charges (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    lease_id BIGINT NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    due_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_rent_charges_lease FOREIGN KEY (lease_id) REFERENCES leases(id),
    CONSTRAINT chk_rent_charges_status CHECK (status IN ('PENDING', 'PAID', 'OVERDUE'))
);

CREATE TABLE manual_payments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rent_charge_id BIGINT NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    payment_method VARCHAR(20) NOT NULL,
    notes TEXT,
    recorded_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_manual_payments_charge FOREIGN KEY (rent_charge_id) REFERENCES rent_charges(id),
    CONSTRAINT chk_manual_payments_method CHECK (payment_method IN ('CASH', 'CHECK', 'OTHER'))
);

-- ============================================================================
-- Seed data
-- ============================================================================

-- Property manager
INSERT INTO property_managers (id, name, email) VALUES
    (1, 'Greenfield Property Group', 'admin@greenfieldproperties.com');

-- Properties
INSERT INTO properties (id, pm_id, name, address) VALUES
    (1, 1, 'Sunset Apartments', '100 Sunset Blvd, Los Angeles, CA 90028'),
    (2, 1, 'Riverside Commons', '250 River Rd, Austin, TX 78701');

-- Units
INSERT INTO units (id, property_id, unit_number) VALUES
    (1, 1, '101'),
    (2, 1, '102'),
    (3, 2, 'A1'),
    (4, 2, 'A2');

-- Tenants
INSERT INTO tenants (id, first_name, last_name, email, phone) VALUES
    (1, 'Alice', 'Johnson', 'alice.johnson@email.com', '555-0101'),
    (2, 'Bob', 'Smith', 'bob.smith@email.com', '555-0102'),
    (3, 'Carol', 'Williams', 'carol.williams@email.com', '555-0103');

-- Leases (Alice in unit 101, Bob in unit A1 — both active)
INSERT INTO leases (id, tenant_id, unit_id, rent_amount, start_date, end_date, status) VALUES
    (1, 1, 1, 2500.00, '2025-01-01', '2026-01-01', 'ACTIVE'),
    (2, 2, 3, 1800.00, '2025-03-01', '2026-03-01', 'ACTIVE');

-- Rent charges for current month
INSERT INTO rent_charges (id, lease_id, amount, due_date, status) VALUES
    (1, 1, 2500.00, '2025-06-01', 'PENDING'),
    (2, 1, 2500.00, '2025-05-01', 'PAID'),
    (3, 2, 1800.00, '2025-06-01', 'PENDING'),
    (4, 2, 1800.00, '2025-05-01', 'PAID');

-- Manual payments (May charges were paid by check)
INSERT INTO manual_payments (id, rent_charge_id, amount, payment_method, notes, recorded_by) VALUES
    (1, 2, 2500.00, 'CHECK', 'Check #1042', 'admin@greenfieldproperties.com'),
    (2, 4, 1800.00, 'CASH', NULL, 'admin@greenfieldproperties.com');
