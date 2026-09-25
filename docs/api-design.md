# API Design — Credit Card Rent Payments (Stripe)

Design proposal for adding tenant credit-card payments via Stripe, per `TASK.md`.
Existing conventions preserved: `/api/` prefix, cursor pagination (`startAfterId`, `limit`),
`@PreAuthorize` role checks, and 404-instead-of-403 when a tenant touches a resource
that isn't theirs (see `TenantApi.get`).

## Implementation order

- **Phase 1 — core Stripe flow** (TASK.md scope): card management, `POST
  .../pay` with server-derived amount, payment lifecycle statuses, Stripe
  webhook transitions, payment reads (`GET /api/payments`, `/{id}`,
  `?rentChargeId=`).
- **Phase 2 — notifications** (UX requirement, built last): `outbox_events`,
  `payment-events` queue + `PaymentEventsWorker`, `notifications` table,
  `GET /api/notifications*` + SSE stream. Phase 1 already writes status
  transitions atomically, so phase 2 only adds the outbox row beside them —
  no rework of the core flow.

## Requirements → API mapping

| # | Requirement | Covered by |
|---|---|---|
| 1 | Tenant views past payments | `GET /api/payments` (tenant-scoped list), `GET /api/payments/{id}` |
| 2 | Tenant views payment status | `status` field on `PaymentResponse` |
| 3 | Tenant notified on success/fail/in-progress | Status transition + `outbox_events` row (same tx) → relay → `payment-events` queue → consumer writes `notifications` row **and pushes via SSE**; `GET /api/notifications/stream` (push), `GET /api/notifications` (history) |
| 4 | Tenant views payment methods | `GET /api/cards` |
| 5 | Tenant adds a payment method | `POST /api/cards/setup-intent` + Stripe.js confirm → `setup_intent.succeeded` webhook persists card + pushes `CARD_ADDED` |
| 6 | Tenant removes a payment method | `DELETE /api/cards/{id}` → outbox `CARD_REMOVED` event pushed via SSE |
| 7 | Payment amount = charge balance, client only picks method | `POST /api/rent-charges/{id}/pay` takes `{ cardId }` only; amount is derived server-side |

## Principles

- **Card data never touches our server.** Cards are collected client-side via Stripe
  (Payment Element / SetupIntent). Our DB stores only Stripe IDs + display metadata
  (brand, last4, expiry).
- **Card payments are asynchronous.** Recording a card payment returns a payment
  resource immediately; its status advances via Stripe webhooks. This is the core
  difference from manual payments, which are recorded already-settled.
- **Idempotent by design.** Retried pay requests must not double-charge or
  double-record.
- **Manual payments keep working unchanged** for PMs (`CASH`, `CHECK`, `OTHER`).

## Payment lifecycle

```
                 ┌─────────────┐
                 │  INITIATED  │  PaymentIntent created, not yet confirmed
                 └──────┬──────┘
          ┌─────────────┼─────────────────┐
          ▼             ▼                 ▼
   ┌────────────┐ ┌───────────┐    ┌───────────┐
   │ REQUIRES_  │ │ PROCESSING│    │ SUCCEEDED │──▶ ┌──────────┐
   │ ACTION     │ └─────┬─────┘    └───────────┘    │ REFUNDED │
   │ (3DS etc.) │       │                           └──────────┘
   └─────┬──────┘       ▼
         │        ┌──────────┐
         └───────▶│  FAILED  │  (declined / canceled — terminal)
                  └──────────┘
```

| Status | Meaning | Trigger |
|---|---|---|
| `INITIATED` | Payment row + Stripe PaymentIntent created | `POST .../pay` |
| `REQUIRES_ACTION` | Stripe needs customer action (3DS/SCA) | PI `requires_action`; `clientSecret` returned to client |
| `PROCESSING` | Charge is being processed asynchronously | PI `processing` |
| `SUCCEEDED` | Funds captured; flips rent charge → `PAID` | `payment_intent.succeeded` |
| `FAILED` | Declined or canceled (terminal) | `payment_intent.payment_failed` / `canceled` |
| `REFUNDED` | Succeeded payment was refunded (terminal) | `charge.refunded` / refund event |

Manual payments are inserted directly as `SUCCEEDED`.

## Endpoints

### Card management (TENANT only — always scoped to caller's tenant)

#### `POST /api/cards/setup-intent`
Creates a Stripe `SetupIntent` for the tenant's Stripe Customer (creating the
Customer lazily on first call). The client uses `clientSecret` + Stripe.js to
collect card details and attach the resulting PaymentMethod to the Customer.

The local `cards` row is created when Stripe's `setup_intent.succeeded`
webhook arrives — we persist from Stripe's confirmation, not a client callback.
The same webhook emits a `CARD_ADDED` outbox event, so a connected client sees
the new card pushed over SSE as soon as it lands.

Response `201`:
```json
{ "clientSecret": "seti_1Xxx_secret_yyy" }
```

#### `GET /api/cards`
Lists the caller's saved cards. Cursor-paginated like other list endpoints.

Response `200`:
```json
{
  "content": [
    { "id": 7, "brand": "visa", "last4": "4242", "expMonth": 12, "expYear": 2028, "createdAt": "..." }
  ],
  "hasMore": false
}
```

#### `DELETE /api/cards/{id}`
Detaches the card from Stripe and removes it locally. `404` if the card doesn't
exist or belongs to another tenant. Writes a `CARD_REMOVED` outbox event in the
same transaction → pushed via SSE. Response `204`.

### Paying a rent charge (TENANT only)

#### `POST /api/rent-charges/{id}/pay`
Charges one of the tenant's saved cards for a rent charge they own.

Headers: `Idempotency-Key: <string>` (optional; stored on the payment row —
a retry with the same key returns the existing payment instead of charging again).

The request contains **only the payment-method selection** — the amount is never
client-supplied. The server charges the charge's remaining balance:
`charge.amount − sum(SUCCEEDED payments on that charge)`. This prevents a tenant
from under/over-paying by tampering with the request.

Request:
```json
{ "cardId": 7 }
```

Response `202` — payment accepted, lifecycle in progress:
```json
{
  "id": 42,
  "rentChargeId": 3,
  "amount": 1800.00,
  "paymentMethod": "CREDIT_CARD",
  "status": "PROCESSING",
  "card": { "brand": "visa", "last4": "4242" },
  "clientSecret": null,
  "failureReason": null,
  "notes": null,
  "recordedBy": "alice.johnson@email.com",
  "createdAt": "..."
}
```

- `status: "REQUIRES_ACTION"` → `clientSecret` is populated; client completes 3DS
  via Stripe.js, then lifecycle resumes via webhook.
- `status: "FAILED"` → synchronous decline; `failureReason` populated.
- `404` if the charge doesn't exist or doesn't belong to the caller's leases.
- `409 Conflict` if the charge already has a `SUCCEEDED` payment or an in-flight
  (`INITIATED`/`REQUIRES_ACTION`/`PROCESSING`) card payment.

### Reading payments

#### `GET /api/payments` (extended — tenant payment history)
No-params list, scoped by caller role like `GET /api/leases`:
- TENANT → all of their own payments across all their leases, newest… (cursor-paged)
- PM → all payments (optionally filterable by `rentChargeId` below)

#### `GET /api/payments/{id}` (new)
Tenants see only their own payments; PMs see all. `404` on non-existent / not-owned.

#### `GET /api/payments?rentChargeId={id}` (extended)
Existing endpoint. PM behavior unchanged. Now also allows TENANT if the charge
belongs to them — otherwise `404` (not 403, to avoid leaking existence).

`PaymentResponse` gains nullable fields (all null for manual payments):
`status`, `card`, `failureReason`. `clientSecret` only ever appears on the
`POST .../pay` response.

### Stripe webhook

#### `POST /api/webhooks/stripe`
- Unauthenticated; verified via `Stripe-Signature` header + webhook secret.
- Handles `payment_intent.succeeded`, `payment_intent.payment_failed`,
  `payment_intent.canceled`, `payment_intent.processing`, refund events;
  transitions payment status and marks the rent charge `PAID` on success.
- Also handles `setup_intent.succeeded` — persists the new `cards` row (brand,
  last4, expiry from the attached PaymentMethod).
- **Every transition/lifecycle event writes an `outbox_events` row in the same
  DB transaction**: payment statuses (`PROCESSING`, `SUCCEEDED`, `FAILED`,
  `REQUIRES_ACTION`, `REFUNDED`) and card events (`CARD_ADDED` — from the
  webhook, `CARD_REMOVED` — from `DELETE /api/cards/{id}`). Delivery happens
  asynchronously through the local queue — see "Event pipeline" below.
- Idempotent: duplicate deliveries are no-ops (PI id is unique-keyed).
- Returns `200` quickly; heavy work deferred if needed.

### Event pipeline (transactional outbox → local SQS)

Notification delivery must not be lost if the process dies between committing
the status change and enqueueing work — so we use the outbox pattern on top of
a **dedicated local SQS queue** (kept separate from `takehome-jobs` so payment
events get their own backlog, retry, and DLQ behavior):

```
webhook / pay endpoint
  └─ TX: UPDATE payments.status + INSERT outbox_events   (atomic)
                                                       │
OutboxRelay (@Scheduled, worker.enabled)                │
  └─ polls unpublished outbox_events ── publishes ──────▶ SQS payment-events
                                                       │
PaymentEventsWorker (@Scheduled poller, worker.enabled) ▼
  └─ INSERT notifications row (idempotent on event_id)
     → push to tenant's open SSE stream (if connected) → delete message
```

`PaymentEventsWorker` dispatches on `event_type`: `PAYMENT_*` events and
`CARD_ADDED`/`CARD_REMOVED` all produce a `notifications` row + SSE push for
the owning tenant — card events surface as messages like "Card visa ••4242
added."

- `outbox_events`: `id`, `aggregate_type` ('PAYMENT' | 'CARD'), `aggregate_id`,
  `event_type` ('PAYMENT_SUCCEEDED', 'CARD_ADDED', 'CARD_REMOVED', …),
  `payload` JSON, `created_at`, `published_at` (null = pending).
- `OutboxRelay` sends messages directly to the new queue (its own SQS client
  call with `aws.sqs.payment-events-queue-url`, mirroring `JobPublisher`), then
  marks `published_at`. The message body carries `event_id` + `event_type` +
  payload.
- `PaymentEventsWorker` is a second scheduled poller modeled on `SqsWorker`,
  consuming `payment-events` and dispatching on `event_type` — today only
  `PAYMENT_*` → write a `notifications` row. Failed messages rely on SQS
  visibility-timeout redelivery, then the DLQ.
- Infra change: `localstack-init/init-aws.sh` creates `payment-events` +
  `payment-events-dlq` (same convention as `takehome-jobs`/`takehome-jobs-dlq`).
  No new LocalStack services — `sqs` is already enabled.
- Delivery is **at-least-once**: the notification writer dedupes on
  `outbox_events.id` (unique `event_id` column on `notifications`).
- A dead-lettered/replayed event just produces a skipped duplicate — safe.

### Notifications (TENANT only — scoped to caller)

#### `GET /api/notifications/stream` — real-time push (SSE)
Long-lived `text/event-stream` connection (Spring `SseEmitter`). When
`PaymentEventsWorker` consumes a payment event for this tenant, it writes the
`notifications` row **and emits the event on this stream in the same step** —
no client polling while connected.

```
event: PAYMENT_SUCCEEDED
data: {"id":15,"paymentId":42,"type":"PAYMENT_SUCCEEDED","message":"Your payment of $1800.00 succeeded.","createdAt":"..."}
```

- On connect, the client can replay missed items via `GET /api/notifications`
  (e.g. `?unread=true`) before/while the stream delivers live events.
- Emitters are held in-process (`tenantId → SseEmitter` registry). Fine for a
  single instance; at multi-instance scale each node only sees events its own
  consumer processed — production would broadcast via Redis pub/sub or similar.
  (Good "what I'd do at scale" README material.)

#### `GET /api/notifications`
Cursor-paged list of the caller's payment notifications, newest first.
`?unread=true` filters to unread — used for catch-up after SSE reconnect.

Response `200`:
```json
{
  "content": [
    {
      "id": 15,
      "paymentId": 42,
      "type": "PAYMENT_SUCCEEDED",
      "message": "Your payment of $1800.00 succeeded.",
      "readAt": null,
      "createdAt": "..."
    }
  ],
  "hasMore": false
}
```

#### `POST /api/notifications/{id}/read`
Marks one notification read. `404` if not found / not owned. Response `204`.

> Note: notifications are in-app — pushed live over SSE when connected, with
> `GET /api/notifications` as the durable fallback. There is no email/push infra
> in this repo; SES-via-LocalStack is a possible extension but out of scope.

## Error semantics

| Case | Status |
|---|---|
| Validation failure (`@Valid`) | `400` — existing `GlobalExceptionHandler` |
| Charge/card not found or not yours | `404` |
| Charge already paid / payment in flight | `409` |
| Card declined | `202` with `status: FAILED` + `failureReason` (the attempt succeeded as an operation) |
| Stripe API unreachable at initiation | `502` |
| Webhook bad signature | `400` |

## Data model implications (for the migration that follows)

- `payments` += `status`, `stripe_payment_intent_id` (unique), `idempotency_key`
  (unique), `failure_reason`; `payment_method` CHECK extended with `CREDIT_CARD`.
- New `cards` table: `tenant_id`, `stripe_payment_method_id` (unique), `brand`,
  `last4`, `exp_month`, `exp_year`.
- New `notifications` table: `tenant_id`, `type`, `message`, nullable
  `payment_id` / `card_id` refs, `event_id` (unique — dedupe key from
  `outbox_events.id`), `read_at` (null = unread).
- New `outbox_events` table: `aggregate_type`, `aggregate_id`, `event_type`,
  `payload`, `published_at` (null = pending).
- `tenants` += `stripe_customer_id` (unique, lazily populated).

## Deliberate scope cuts

- Refunds: status modeled, but no refund-initiation endpoint in v1.
- No saved-bank/ACH methods — `CREDIT_CARD` only.
- `REQUIRES_ACTION` is modeled but the 3DS client flow is documented, not built
  (no frontend exists — decision: backend-only; the browser→Stripe.js flow is
  described for integrators but not shipped).

## Open questions

1. Endpoint naming: `POST /api/cards/setup-intent` vs `/api/cards/setup-session` —
   former is more Stripe-transparent; fine either way.
2. Should PMs get a "record card payment on behalf of tenant" path? Assumed no —
   cards are tenant-owned and charges are tenant-initiated.
3. Notifications are in-app only (SSE push + persisted history) — no
   email/SMS/push. Acceptable?
4. Pre-existing gap: `GET /api/rent-charges?leaseId=` doesn't check tenant
   ownership — a tenant can enumerate other leases' charges. Worth fixing while
   we're in here (out of scope but flagging).
