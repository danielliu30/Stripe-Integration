# Property Management Platform — Backend Take-Home

A simplified property management API built with Kotlin + Spring Boot. This is a starter repository for the backend engineering take-home exercise.

## What's Included

This repo contains a working property management platform with:

- **Entities**: Property managers, properties, units, tenants, leases, rent charges, and manual (offline) payments
- **REST API**: Full CRUD for all entities with pagination and role-based access control
- **Authentication**: JWT-based auth with login endpoint, two roles (TENANT, PROPERTY_MANAGER)
- **Database**: MySQL 8 with Flyway migrations and seed data
- **S3 Integration**: File storage service wired to LocalStack S3
- **SQS Background Worker**: Polling-based job processor with an example job (rent charge generation)
- **Tests**: Unit tests with MockK, controller tests with MockMvc + JWT auth

## Prerequisites

- JDK 17+
- Docker and Docker Compose

## Quick Start

```bash
# Start infrastructure (MySQL + LocalStack)
docker-compose up -d

# Run the API server
./gradlew bootRun

# Run the background worker (in a separate terminal)
./gradlew bootRun --args='--worker.enabled=true'

# Run unit tests
./gradlew test

# Run integration tests (Docker required — spins up ElasticMQ via Testcontainers)
./gradlew integrationTest

# Run both
./gradlew test integrationTest
```

The API starts on `http://localhost:8080`.

## Authentication

All API endpoints (except `/api/auth/**`) require a valid JWT token in the `Authorization` header.

### Login

```bash
# Login as property manager
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email": "admin@greenfieldproperties.com", "password": "password"}'

# Login as tenant
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email": "alice.johnson@email.com", "password": "password"}'
```

Response:
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "userId": 1,
  "email": "admin@greenfieldproperties.com",
  "role": "PROPERTY_MANAGER"
}
```

### Using the Token

```bash
curl http://localhost:8080/api/leases \
  -H 'Authorization: Bearer <token>'
```

### Seeded Users

| Email | Password | Role |
|-------|----------|------|
| admin@greenfieldproperties.com | password | PROPERTY_MANAGER |
| alice.johnson@email.com | password | TENANT |
| bob.smith@email.com | password | TENANT |
| carol.williams@email.com | password | TENANT |

### Role-Based Access

- **Property managers** can manage properties, units, tenants, leases, and record manual payments
- **Tenants** can view their own leases and rent charges
- All authenticated users can access lease and rent charge read endpoints

## Pagination

All list endpoints use cursor-based pagination with `startAfterId` and `limit` query parameters. This avoids the performance and consistency problems of offset-based pagination.

```bash
# First page (default limit: 20)
curl http://localhost:8080/api/tenants -H 'Authorization: Bearer <token>'

# Next page — pass the last item's ID as startAfterId
curl 'http://localhost:8080/api/tenants?startAfterId=20&limit=10' \
  -H 'Authorization: Bearer <token>'
```

Response format:
```json
{
  "content": [...],
  "hasMore": true
}
```

## API Endpoints

### Authentication
- `POST   /api/auth/login` — Authenticate and receive JWT token

### Tenants (PM only for list/create/update)
- `GET    /api/tenants` — List all tenants (paginated)
- `GET    /api/tenants/{id}` — Get tenant by ID
- `POST   /api/tenants` — Create tenant
- `PUT    /api/tenants/{id}` — Update tenant

### Properties & Units (PM only)
- `GET    /api/properties` — List all properties (paginated)
- `GET    /api/properties/{id}` — Get property by ID
- `POST   /api/properties` — Create property
- `GET    /api/properties/{id}/units` — List units for a property (paginated)
- `POST   /api/properties/{id}/units` — Create unit

### Leases
- `GET    /api/leases` — List leases (tenants see only their own, PMs see all; paginated)
- `GET    /api/leases/{id}` — Get lease by ID
- `GET    /api/leases?tenantId={id}` — Get leases by tenant (PM only; paginated)
- `POST   /api/leases` — Create lease (PM only)

### Rent Charges
- `GET    /api/rent-charges/{id}` — Get rent charge by ID
- `GET    /api/rent-charges?leaseId={id}` — Get charges by lease (paginated)
- `GET    /api/rent-charges?leaseId={id}&status=PENDING` — Filter by status (paginated)

### Manual Payments (PM only)
- `GET    /api/manual-payments?rentChargeId={id}` — Get payments for a charge (paginated)
- `POST   /api/manual-payments` — Record a manual payment

## Architecture

```
src/main/kotlin/com/ender/takehome/
├── config/          # Security, JWT, AWS, Jackson configuration
├── model/           # JPA entities
├── repository/      # Spring Data JPA repositories (with @EntityGraph for N+1 prevention)
├── service/         # Business logic
├── controller/      # REST controllers with pagination and auth
├── worker/          # SQS background job processor
├── dto/             # Request/response DTOs
└── exception/       # Error handling
```

### Infrastructure

| Service    | Local               | Purpose                        |
|------------|---------------------|--------------------------------|
| MySQL 8    | Docker (port 3306)  | Primary database               |
| LocalStack | Docker (port 4566)  | S3 file storage + SQS queues   |

### Seed Data

The migration creates sample data: 1 property manager, 2 properties, 4 units, 3 tenants, 2 active leases, rent charges with manual payments, and 4 user accounts.

## Your Task

See **[TASK.md](./TASK.md)** for the assignment.
