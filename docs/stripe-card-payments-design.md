# Stripe Card Payments: Design Decisions and Trade-offs

## The problem

The task was to let tenants pay rent charges by credit card. Before this, the platform only recorded offline payments — cash and checks that a property manager typed in by hand.

I broke the feature into three parts:

1. **Saving a card** — tenants store cards for reuse, without my API ever touching a card number.
2. **Charging a saved card** — a tenant picks a rent charge, and the server runs it through Stripe.
3. **Tracking payment state** — card payments take time (initiated, processing, succeeded, failed, refunded), and the model has to represent all of it, including the nasty case where Stripe's answer never makes it back.

The hard part is that we're **moving money across an unreliable boundary**. A call to Stripe can succeed, fail cleanly, or end with nobody knowing what happened — a timeout, a dropped connection, a crash mid-request. Everything below follows from taking those three cases seriously: no double charges, no lost charges, no payments stuck in limbo.

One term that comes up a lot: **idempotency** means a request can be safely retried without doing the work twice. It's what makes "try again" safe when money is on the line.

## Architecture at a glance

```
Tenant                         This service                            Stripe
──────                         ──────────────                          ──────
Add card ──→ POST /cards/checkout-session ─────────────────────→ creates Checkout Session
          ←── redirectUrl (hosted page where card is entered) ──→ (tenant types card to Stripe)
             POST /webhooks/stripe ←────── setup_intent.succeeded ── saves card metadata

Pay rent ──→ POST /rent-charges/{id}/pay
             (Idempotency-Key: caller-generated UUID)
               │  1. DB transaction: validate ownership, lock charge,
               │       insert payment (INITIATED) + recovery row
               │  2. Stripe PaymentIntent create+confirm  ←── outside the transaction
               │  3. DB transaction: settle → SUCCEEDED / FAILED
             ←── 202 Accepted (payment + current status)

If step 2's outcome is unknown:
             payment stays INITIATED; recovery row stays PENDING
               │  dispatcher → SQS → worker retries with the SAME Stripe key
               └──→ Stripe returns the original PaymentIntent → settled

Meanwhile, at any time:
             POST /webhooks/stripe ←── payment_intent.* / charge.refunded
               └── reconciles payment state if the event arrives first
```

In plain terms: the tenant saves a card on Stripe's own hosted page, and a webhook tells me it worked. To pay, I write a payment row, call Stripe, then write the result — two short database transactions with the network call in between. If Stripe's answer gets lost along the way, a background job retries with the same idempotency key, and Stripe hands back the original result instead of charging the card again.

Two properties do the heavy lifting:

- **The payment row is saved before Stripe is ever called.** A payment can never exist only in Stripe's memory of it.
- **One idempotency key travels the whole path** — client → application → database → Stripe → queue retry — so every layer can recognize a retry when it sees one.

## Technology choices, and why

**Stripe hosted Checkout for card entry.** This one was mostly about PCI. If card numbers ever touch my servers, I take on full PCI-DSS obligations — Stripe's hosted page keeps card data off my infrastructure entirely and shrinks the compliance burden to the minimum tier. The trade: card setup finishes asynchronously via webhook, and I'm dependent on Stripe's page UX.

**MySQL transactions, row locks, and unique constraints.** It's already the project's stack, and payment correctness needs transactional guarantees anyway. The charge-row lock makes sure only one payment attempt per charge runs at a time, and the unique constraint on the idempotency key is the last word on "one payment per request." No new infrastructure to justify.

**SQS for recovery (LocalStack locally).** The project already had a worker polling a queue for rent-charge generation, so this reuses a proven path. But the deeper reason is decoupling: the API process that saw the uncertain outcome might die, and the retry has to survive it — it can't live in a dead process's memory. SQS also gives me redelivery, backoff, and a dead-letter queue for free.

**jOOQ where contention matters.** Claiming a recovery and reloading a race loser need atomic "update only if still in state X" queries. JPA loads entities and writes them back, which can't express that cleanly — jOOQ just writes the SQL.

**Off-session PaymentIntent `create+confirm`.** This is the saved-card charge path Stripe recommends. The tenant already authorized future off-session use during card setup, so a pay call is one round-trip with an immediate answer.

## Synchronous vs asynchronous

The rule I used: **sync when a caller is waiting and the outcome is knowable now; async as soon as the outcome isn't mine to produce.**

| Operation | Mode | Why |
|---|---|---|
| Card save | Async (webhook confirms) | Hosted checkout is inherently async — the user leaves our page |
| Payment attempt | Sync | The caller is waiting; Stripe `confirm` answers fast enough |
| Uncertain-outcome retry | Async (queue) | There's no answer to give; the retry belongs to background machinery that outlives the request |
| Lifecycle updates (processing→succeeded, refunds) | Async (webhooks) | Stripe moves these on its own schedule |
| Charge generation, recovery dispatch | Async (scheduled + queue) | Nobody is waiting on the other end |

## Card management

Cards are collected through Stripe-hosted Checkout Sessions in setup mode. Card numbers never touch the API — I only persist brand, last4, and expiry.

A card counts as saved when the `setup_intent.succeeded` webhook arrives — not when the user comes back from the Checkout page. The redirect is just browser navigation (people close tabs), while the webhook is signed by Stripe and retried if I miss it.

**Deletion is soft.** `DELETE /api/cards/{id}` sets `deleted_at` and detaches the Stripe PaymentMethod. The row survives so historical payments keep their card context (`visa ••••4242`), but the card can't be used for anything new.

One detail worth noting: if Checkout-session creation fails after I've already created the Stripe Customer, I keep the saved `stripe_customer_id`. The Customer represents the tenant permanently; a Session is disposable. Retries reuse the Customer instead of piling up duplicates in Stripe.

## Payment workflow

A pay request identifies a rent charge and a saved card. The amount comes from the outstanding balance — the client picks the card, never the amount.

Three phases:

1. **Prepare (DB transaction)** — authenticate, verify the charge belongs to the tenant and the card is theirs, lock the charge row, reject already-paid and in-flight charges, insert the `INITIATED` payment **and** its recovery row in the same commit.
2. **Charge (Stripe, outside the transaction)** — create + confirm an off-session PaymentIntent with the persisted key and metadata (`paymentId`, `rentChargeId`).
3. **Settle (DB transaction)** — store the PaymentIntent ID, status, and failure reason; mark the charge `PAID` only on success; complete the recovery row.

Why three operations instead of one transaction? Two reasons:

- **Atomicity with Stripe is impossible.** A remote HTTP call can't participate in a MySQL transaction — a single transaction was never actually on the table.
- **Holding the transaction open across the call buys nothing.** The pooled DB connection and the charge-row lock would sit open for Stripe's whole response time, and slow Stripe drains the connection pool and stalls unrelated tenants. And even a clean commit followed by a crash still leaves the Stripe outcome unknown — that uncertain window exists either way.

So the call goes outside. The cost is a visible `INITIATED` state, which the recovery machinery needs anyway.

## Idempotency

No single mechanism covers every failure window, so I layered three of them:

| Layer | Covers | Effect |
|---|---|---|
| Application lookup | Sequential retry after a payment exists | Returns it; no DB write, no Stripe call |
| DB uniqueness on `payments.idempotency_key` | Concurrent requests that both miss the lookup | One insert wins; the loser reloads the winner |
| Stripe idempotency key | Request sent, response lost | Stripe returns the original PaymentIntent |

Each layer earns its place. Remove one and a concrete failure shows up:

| Remove | What happens |
|---|---|
| Application lookup | A normal retry hits the unique constraint and comes back as a `409`/exception instead of returning the existing payment — every legitimate retry becomes an error path |
| Unique constraint | Two identical requests arrive together, both miss the lookup, both insert a payment row and call Stripe. Stripe's key still prevents a double charge, but the database now holds two payments for one logical attempt |
| Stripe key | A charge succeeds at Stripe but the response is lost; the retry creates a *second* PaymentIntent — the tenant is charged twice |

### Who generates the key

The **calling client** — frontend, mobile app, or script. Not the human user, and not Stripe. The rules I chose:

- Non-blank, at most 255 characters, unique per attempt — a UUID is the intended format.
- One logical attempt gets one key; reuse it only when retrying that same attempt.

The server can't invent the key: if the first response is lost, only a client-held key proves a retry is the same operation and not a new payment.

### Accepted trade-off: global key scope

Keys are globally unique — not scoped per tenant. Two consequences:

- **Safe:** a cross-tenant collision gets rejected by ownership checks. It can't leak data or double-charge.
- **Imperfect:** a weak key from one tenant could theoretically block another tenant that picked the same key. UUID entropy makes this negligible.

Global scope keeps the app, the database, and Stripe aligned under one identifier, which is why I kept it. In production I'd tighten this with `UNIQUE (tenant_id, key)` plus tenant-namespaced Stripe keys.

## `202 Accepted` and server-owned recovery

The pay endpoint always returns `202` — durable acceptance, not final success:

- **Definite outcomes** (succeeded, declined, validation errors) settle synchronously and come back with their real status.
- **Uncertain outcomes** (timeout, dropped connection — Stripe may or may not have charged) leave the payment `INITIATED` with a `PENDING` recovery, and the **server** takes over the retry.

I deliberately didn't make the client retry. The client can't tell "Stripe never got it" from "charged but the answer was lost," so it would just resend the same key anyway — putting correctness in the caller's hands for no benefit. Owning the retry server-side makes the safe path the only path.

Recovery is a small version of the outbox pattern — a database row written with the payment, describing work that still has to happen:

- The `payment_recoveries` row commits in the same transaction as the payment, so a payment can never exist without a recorded instruction to retry it. That row is the safety net: even if the API process dies mid-call, the retry survives in the database.
- The dispatcher claims due rows atomically (`PENDING → PUBLISHING → PUBLISHED`), which lets multiple app instances dispatch concurrently without double-publishing. A crash between publishing and marking produces at worst a duplicate message — which the handler safely ignores.
- Queue messages carry only `paymentId`. The worker reloads the key, card, amount, and customer from the database, so payment parameters never sit in queue payloads.
- `executeInitiatedPayment` does nothing unless the payment is still `INITIATED`, so duplicate deliveries and a webhook that wins the race are both harmless.
- New recoveries get a short **initial delay** before their first dispatch. The synchronous response or a webhook usually settles the payment first, and an immediate retry would mostly race work already in flight.
- Failed worker attempts retry with **capped exponential backoff and jitter** via SQS visibility timeouts. After the retry cap, messages land on a **dead-letter queue** for inspection.

## Webhook reconciliation

Stripe's callback channel reconciles anything the synchronous path missed, plus state that changes later (like refunds):

| Condition | Response | Why |
|---|---|---|
| Missing/invalid signature | `400` | Unauthenticated — reject |
| Valid signature, supported event | `200` | Processed |
| Valid signature, unsupported/malformed payload | `200` | Authentic but permanently unprocessable — redelivery can't fix it |
| Valid event, transient downstream failure | `5xx` | A later Stripe retry may succeed |

Worth spelling out: acknowledging a malformed signed event is "acknowledge and ignore," not fail-open. Nothing mutates, and unauthenticated requests still get rejected.

## Error contract

| Situation | Response |
|---|---|
| Missing `Idempotency-Key` | `400` |
| Blank/oversized key | `400` |
| Bad login credentials | `401` (identical for unknown email / wrong password) |
| Authenticated, wrong role | `403` |
| Key replay against a different charge/tenant | `404`/`409` — never returns another tenant's payment |
| Stripe unreachable for card setup | `502` |
| Stripe outcome uncertain for a charge | `202` with `INITIATED` |

## Testing strategy

I tested each boundary at the cheapest layer that exercises it:

- **Unit** — retry policy math, exception mapping, webhook parsing, module logic against a fake Stripe boundary.
- **HTTP integration** — real Spring + Flyway + jOOQ + H2 with a deterministic Stripe fake: replay, race recovery, error contract, signature handling.
- **Queue/persistence integration** — ElasticMQ + Testcontainers: dispatcher claim contention, worker visibility backoff, duplicate delivery.
- **Real-Stripe E2E** (`stripeIntegrationTest`) — real Customers, PaymentMethods, and PaymentIntents. My favorite test here is the connected recovery proof: a wrapper drops the Stripe response after a real charge, the API returns `202`, and dispatcher → SQS → worker settles the payment via the persisted key with no manual intervention.
- **Compose smoke** — a fresh Docker stack exercised over real HTTP.

The deterministic suites catch application regressions without Stripe; the real suite catches SDK, contract, or account drift. All of them run in GitLab CI.

## Limitations

- **Partial refunds ignored** — only fully refunded charges reconcile; there's no partial-refund state.
- **No autopay** — recurring charges are a future extension.
- **US cards only, for now** — the charge path assumes US-style authorization, where a saved card can be charged without the cardholder present. EU/UK and other banks can demand authentication (3DS/SCA), which returns the payment as `REQUIRES_ACTION` — and an off-session charge has no user present to complete it. I persist the status rather than mark it failed, but nothing drives it forward. Supporting foreign banks means adding a notify-the-tenant flow with an on-session retry.
- **Global idempotency keys** — the trade-off described above.
- **DLQ is unmonitored** — messages that exhaust their retries are stored for inspection, but nothing alerts anyone they're there.

## What I'd change for production at scale

- Per-tenant key scoping (`UNIQUE (tenant_id, key)` + tenant-namespaced Stripe keys).
- Metrics and alerting: recovery lag, Stripe error rate, DLQ depth.
- Webhook ordering hardening — events can arrive out of order; I'd guard stale events from overwriting newer state.
- A timeout budget on the synchronous Stripe call so slow Stripe can't exhaust request threads.
- Separate worker deployment — the dispatcher and worker already scale horizontally (atomic claims + competing consumers), but isolating them protects API traffic.
- Rate limiting on the pay endpoint.

## AI usage

I used [Devin](https://devin.ai) (Cognition) to build this feature, working in small merge requests I reviewed and merged one at a time.

- **Devin wrote:** all application code (payment workflow, idempotency, recovery outbox/dispatcher, webhook handler, error contract), all migrations, all tests across the five suites above, the CI pipeline (including the Testcontainers fix for the real-Stripe E2E job), the README, and this document.
- **Devin did, under my direction:** ran the local verification walkthroughs, drafted documentation and trade-off write-ups, and debugged CI failures.
- **I did:** drove the design exploration — chose the architecture, evaluated alternatives, and made the trade-off calls this document records. I also set scope and priorities per iteration, reviewed and merged every MR, provided the secrets and accounts (Stripe, GitLab), and decided which deferred trade-offs (global keys, unmonitored DLQ, no autopay) were acceptable to ship.

Every change was verified through the test suites above and reviewed by me before merge — nothing shipped on AI output alone.
