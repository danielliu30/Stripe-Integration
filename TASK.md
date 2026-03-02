# Take-Home Assignment: Credit Card Rent Payments

## The Feature

Add the ability for a **tenant to pay rent using a credit card via Stripe**.

The platform currently supports recording manual/offline payments (cash, check). Your task is to integrate Stripe as an online payment method so tenants can pay their rent charges directly through the API.

### What the feature must support

**Card management** — Tenants need a way to add credit cards to their account. A tenant may have multiple saved cards. Cards should be stored via Stripe (not raw card numbers in our database). The card setup flow should use Stripe's recommended approach for securely collecting and storing payment methods.

**Paying with a saved card** — In addition to the existing offline payment methods, tenants should be able to pay a rent charge using one of their saved cards. The system charges the card via Stripe and records the result. A tenant should not be able to pay a charge that doesn't belong to them.

**Payment lifecycle** — Credit card payments are not instant. Your data model should represent the full payment lifecycle as it moves through Stripe (e.g., a payment that is initiated, one that succeeds, one that fails, one that is refunded). The set of statuses you choose and how transitions between them work is part of the design.

## Quality Expectations

This is a senior/staff-level exercise. We expect code that is:

- **Production-grade** — not a prototype. Handle edge cases, validate inputs, fail gracefully.
- **Supportable** — another engineer should be able to read, debug, and extend your code without a walkthrough from you.
- **Scalable** — your design should not have inherent bottlenecks. Think about what happens when this runs across thousands of tenants paying simultaneously.
- **Tested** — unit tests for core business logic at minimum. Integration tests that exercise the full flow are a strong signal.

## AI Policy

AI tools (ChatGPT, Claude, Copilot, etc.) are **allowed and expected**.

## Show Your Work

Include the intermediate artifacts you produced on your way to the final implementation — plans, design documents, research, etc. We want to see how you got here, not just where you ended up.

## Commit History

We review your commit history as part of the evaluation. Structure your commits thoughtfully.

## Deliverables

When you're done, your fork should include:

1. **Working code** implementing the Stripe payment feature (card management, payment flow, lifecycle statuses)
2. **Database migration(s)** for any new tables or columns
3. **Tests** for core business logic (unit tests required; integration tests strongly encouraged)
4. **AI artifacts** — any intermediate materials you used to arrive at your implementation
5. **Updated README** covering:
   - Setup and run instructions for your changes
   - Your API design (endpoints, how to use them, example requests/responses)
   - Data model additions and the payment status lifecycle
   - Assumptions and tradeoffs you made
   - What you'd do differently for production / scale
   - How you used AI

## Getting Started

```bash
# Fork this repo, then clone your fork
git clone <your-fork-url>
cd be-take-home

# Start infrastructure
docker-compose up -d

# Verify the baseline works
./gradlew test
./gradlew bootRun
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email": "alice.johnson@email.com", "password": "password"}'
```

Explore the existing code to understand the patterns and conventions, then start building.

Good luck!
