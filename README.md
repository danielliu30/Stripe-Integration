# Property Management Platform — Backend Take-Home

A simplified property management API built with Kotlin + Spring Boot. This is a starter repository for the backend engineering take-home exercise.

## What's Included

This repo contains a working property management platform with:

- **Entities**: Property managers, properties, units, tenants, leases, rent charges, and manual (offline) payments
- **REST API**: Full CRUD for all entities above
- **Database**: MySQL 8 with Flyway migrations and seed data
- **S3 Integration**: File storage service wired to LocalStack S3
- **SQS Background Worker**: Polling-based job processor with an example job (rent charge generation)
- **Tests**: Unit tests with MockK, controller tests with MockMvc

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
WORKER_ENABLED=true ./gradlew bootRun --args='--worker.enabled=true'

# Run tests
./gradlew test
```

The API starts on `http://localhost:8080`.

## API Endpoints

### Tenants
- `GET    /api/tenants` — List all tenants
- `GET    /api/tenants/{id}` — Get tenant by ID
- `POST   /api/tenants` — Create tenant
- `PUT    /api/tenants/{id}` — Update tenant

### Properties & Units
- `GET    /api/properties` — List all properties
- `GET    /api/properties/{id}` — Get property by ID
- `POST   /api/properties` — Create property
- `GET    /api/properties/{id}/units` — List units for a property
- `POST   /api/properties/{id}/units` — Create unit

### Leases
- `GET    /api/leases` — List all leases
- `GET    /api/leases/{id}` — Get lease by ID
- `GET    /api/leases?tenantId={id}` — Get leases by tenant
- `POST   /api/leases` — Create lease

### Rent Charges
- `GET    /api/rent-charges/{id}` — Get rent charge by ID
- `GET    /api/rent-charges?leaseId={id}` — Get charges by lease

### Manual Payments
- `GET    /api/manual-payments?rentChargeId={id}` — Get payments for a charge
- `POST   /api/manual-payments` — Record a manual payment

## Architecture

```
src/main/kotlin/com/ender/takehome/
├── config/          # AWS and Jackson configuration
├── model/           # JPA entities
├── repository/      # Spring Data JPA repositories
├── service/         # Business logic
├── controller/      # REST controllers
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

The migration creates sample data: 1 property manager, 2 properties, 4 units, 3 tenants, 2 active leases, and some rent charges with manual payments.

## Your Task

See **[TASK.md](./TASK.md)** for the assignment.
