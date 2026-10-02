# Property Management Platform — Backend Take-Home

A simplified property management API built with Kotlin + Spring Boot. This is a starter repository for the backend engineering take-home exercise.

## What's Included

This repo contains a working property management platform with:

- **Entities**: Property managers, properties, units, tenants, leases, rent charges, and payments
- **REST API**: Full CRUD for all entities with pagination and role-based access control
- **Authentication**: JWT-based auth with login endpoint, two roles (TENANT, PROPERTY_MANAGER)
- **Stripe card payments**: Tenants save cards through Stripe's hosted page and pay rent with them; retries and Stripe callbacks can't double-charge
- **Automatic payment recovery**: If Stripe's answer gets lost mid-request, the server keeps the payment and quietly retries in the background until it settles
- **Database**: MySQL 8 with Flyway migrations and seed data
- **S3 Integration**: File storage service wired to LocalStack S3
- **SQS Background Worker**: Polling-based job processor (rent charge generation, payment recovery)
- **Tests**: Unit tests with MockK, integration tests with MockMvc + Testcontainers, real-Stripe E2E, and a fresh-stack Compose smoke suite

## Prerequisites

- JDK 17+
- Docker and Docker Compose
- A Stripe test account
- [Stripe CLI](https://docs.stripe.com/stripe-cli) (for local webhook testing)

## Setup

The app reads Stripe keys from the environment — `.env` files are not loaded automatically, so export them or `source` a file. Do not commit real keys.

| Variable | Required | Purpose |
|----------|----------|---------|
| `STRIPE_SECRET_KEY` | Yes | Stripe secret key (`sk_test_...`) — the app will not start without it |
| `STRIPE_WEBHOOK_SECRET` | Yes | Signature secret for `POST /api/webhooks/stripe` (`whsec_...`) |
| `STRIPE_CHECKOUT_SUCCESS_URL` | No | Checkout return URL override |
| `STRIPE_CHECKOUT_CANCEL_URL` | No | Checkout cancel URL override |

Follow these steps in order:

**1. Start infrastructure** (MySQL + LocalStack; SQS queue and DLQ are created automatically):

```bash
docker-compose up -d
```

**2. Start webhook forwarding and copy the signing secret.** Authenticate once with `stripe login` (browser) or `STRIPE_API_KEY=<sk_test_...>`, then:

```bash
stripe listen --forward-to localhost:8080/api/webhooks/stripe \
  --events setup_intent.succeeded,payment_intent.succeeded,payment_intent.processing,payment_intent.payment_failed,payment_intent.canceled,charge.refunded
```

Leave it running — it prints a `whsec_...` secret on startup. Cards only appear in `GET /api/cards` after Stripe events arrive through this forwarder.

**3. Export the Stripe variables** in every terminal that runs the app or tests:

```bash
export STRIPE_SECRET_KEY=sk_test_...
export STRIPE_WEBHOOK_SECRET=whsec_...   # printed by `stripe listen`
# or, if you keep them in a local .env:
set -a; source .env; set +a
```

**4. Run the API server:**

```bash
./gradlew bootRun
```

The API starts on `http://localhost:8080`.

**5. Run the background worker** (separate terminal, same env vars) — required for automatic payment recovery and scheduled rent-charge generation; without it those jobs are queued but never processed:

```bash
./gradlew bootRun --args='--worker.enabled=true'
```

## Running the tests

```bash
# Unit tests
./gradlew test

# Integration tests (Docker required — spins up ElasticMQ via Testcontainers)
./gradlew integrationTest

# Both
./gradlew test integrationTest

# Real-Stripe E2E suite (requires STRIPE_SECRET_KEY exported and Docker)
STRIPE_SECRET_KEY=sk_test_... ./gradlew stripeIntegrationTest
```

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

### Cards (tenant only)
- `POST   /api/cards/checkout-session` — Get a link to Stripe's hosted page for adding a card
- `GET    /api/cards` — List the tenant's saved cards
- `DELETE /api/cards/{id}` — Remove a saved card (it's kept in history but can't be used again)

### Card Payments
- `POST   /api/rent-charges/{id}/pay` — Pay a rent charge with a saved card (tenant only; requires an `Idempotency-Key` header)
- `GET    /api/payments` — List the caller's payments (paginated)
- `GET    /api/payments?rentChargeId={id}` — List payments for a charge (PM only; paginated)
- `POST   /api/webhooks/stripe` — Where Stripe sends payment/card updates

## Card Payments

### Adding a card

1. The tenant calls `POST /api/cards/checkout-session` and opens the returned link.

   ```json
   { "redirectUrl": "https://checkout.stripe.com/c/pay/cs_test_..." }
   ```

2. They type their card into Stripe's page — card numbers never pass through this API.
3. Stripe notifies the app, which saves the card's brand, last 4 digits, and expiry. `GET /api/cards` then returns:

   ```json
   {
     "content": [
       {
         "id": 1,
         "brand": "visa",
         "last4": "4242",
         "expMonth": 12,
         "expYear": 2030,
         "createdAt": "2026-10-01T12:00:00Z"
       }
     ],
     "hasMore": false
   }
   ```

### Paying a rent charge

```bash
curl -X POST http://localhost:8080/api/rent-charges/1/pay \
  -H "Authorization: Bearer <tenant-token>" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"cardId": 1}'
```

The API always answers `202 Accepted`; the `status` field in the body says how the payment ended up (`SUCCEEDED`, `FAILED`, `PROCESSING`, or `INITIATED` for "still working on it").

```json
{
  "id": 42,
  "rentChargeId": 1,
  "amount": 1500.00,
  "paymentMethod": "CREDIT_CARD",
  "status": "SUCCEEDED",
  "card": {
    "id": 1,
    "brand": "visa",
    "last4": "4242",
    "expMonth": 12,
    "expYear": 2030,
    "createdAt": "2026-10-01T12:00:00Z"
  },
  "failureReason": null,
  "notes": null,
  "recordedBy": "alice.johnson@email.com",
  "createdAt": "2026-10-01T12:00:00Z"
}
```

**The `Idempotency-Key` header** is a unique label the calling app (website, mobile app, script) makes up for each payment attempt — a UUID works well. If the request might have been lost (timeout, dropped connection), the caller retries with the *same* key and gets the original payment back instead of a duplicate charge. The same key is passed on to Stripe, so even a retry that reaches Stripe twice still produces only one charge.

### What happens when Stripe's answer gets lost

Sometimes the app charges the card but never hears back — the connection drops or times out. Instead of making the caller guess, the server takes over:

```
payment saved as INITIATED + a recovery to-do row  →  202 Accepted
        ↓
a background scheduler picks up due recoveries → posts a retry message to the queue
        ↓
the worker reloads the payment and asks Stripe again with the same key
        ↓
Stripe recognizes the key, returns the original charge → payment settles → charge marked PAID
```

Retries wait a little longer each time (with some randomness so they don't stampede). Messages that keep failing eventually move to a "dead-letter" queue for manual inspection.

### How Stripe talks back

Stripe calls `POST /api/webhooks/stripe` when something happens (card saved, payment succeeded/failed, refund issued). The app checks Stripe's signature on every request — forged requests are rejected with `400`. Events the app doesn't understand are acknowledged with `200` and ignored (they're authentic, just not actionable), while real internal errors return `5xx` so Stripe tries again later.

### Statuses

- Payment: `INITIATED → PROCESSING → SUCCEEDED | FAILED | REQUIRES_ACTION`; `SUCCEEDED → REFUNDED`
- Recovery: `PENDING → PUBLISHING → PUBLISHED → COMPLETED`

### Data model additions

- **`cards`** — one row per saved card: tenant, Stripe PaymentMethod ID, brand/last4/expiry, plus `deleted_at` for soft removal. Card numbers themselves only ever live at Stripe.
- **`tenants.stripe_customer_id`** — links a tenant to their Stripe Customer (created lazily on first card setup).
- **`payments`** (renamed from `manual_payments`) — gained `status`, `card_id`, `stripe_payment_intent_id`, `idempotency_key` (unique), and `failure_reason`.
- **`payment_recoveries`** — a to-do row per payment that lets the background retry machinery find, claim, and finish unfinished payments.

For the reasoning behind these choices — transaction boundaries, idempotency layers, trade-offs, and alternatives — see `docs/stripe-card-payments-design.md`.

## Architecture

```
src/main/kotlin/com/ender/takehome/
├── config/          # Security, JWT, AWS, Jackson configuration
├── model/           # Domain models (payments, recoveries, cards)
├── card/            # Saved-card API and persistence
├── ledger/          # Rent charges, payments, payment recovery data access
├── stripe/          # Stripe client wrapper and webhook event parsing
├── webhook/         # Signed Stripe webhook endpoint
├── worker/          # SQS worker, retry policy, recovery dispatcher
├── service/         # Business logic
├── dto/             # Request/response DTOs
└── exception/       # Error handling
```

### Infrastructure

| Service    | Local               | Purpose                        |
|------------|---------------------|--------------------------------|
| MySQL 8    | Docker (port 3306)  | Primary database               |
| LocalStack | Docker (port 4566)  | S3 file storage + SQS job queue (with DLQ) |
| Stripe     | External            | Card setup (Checkout), off-session charges, webhooks |

### Seed Data

The migration creates sample data: 1 property manager, 2 properties, 4 units, 3 tenants, 2 active leases, rent charges with manual payments, and 4 user accounts.

## Testing

| Suite | Command | Covers |
|-------|---------|--------|
| Unit | `./gradlew test` | Domain logic, retry policy, exception mapping |
| Integration | `./gradlew integrationTest` | HTTP API, persistence, SQS worker/dispatcher via ElasticMQ Testcontainers |
| Stripe E2E | `STRIPE_SECRET_KEY=... ./gradlew stripeIntegrationTest` | Real Stripe card setup, idempotent charges, refunds, and the connected automatic-recovery flow |
| Compose smoke | `./scripts/compose-smoke.sh` | Fresh Docker Compose stack: auth, error contracts, queue wiring |

GitLab CI runs all four suites on every merge request (`unit-and-integration`, `compose-smoke`, `stripe-e2e`).

## Your Task

See **[TASK.md](./TASK.md)** for the assignment.
