# Take-Home Assignment: Credit Card Rent Payments

## The Feature

Add the ability for a **tenant to pay rent using a credit card via Stripe**.

The platform currently supports recording manual/offline payments (cash, check). Your task is to integrate Stripe as an online payment method so tenants can pay their rent charges through the API.

## What You Need to Decide

We are intentionally **not** specifying the API contract. You decide:

- What API endpoints to expose (paths, request/response shapes)
- How payment state is represented and persisted (your data model)
- What is synchronous vs. background processed
- How to handle retries, duplicates, failures, and concurrency
- How to secure and validate the payment flow
- How to make the approach maintainable and extensible for future payment methods

## Stripe Integration

- You may use the real Stripe API (test mode) or mock it — either approach is fine
- The repo should be runnable and testable **without** requiring real Stripe API keys
- Your architecture should make it clear where the Stripe integration lives and how it could be swapped for real calls

## Time Expectation

- **Target**: ~6 hours
- **Hard cap**: 8 hours
- Do not spend more time than the cap. We value good judgment and clear communication of tradeoffs over completeness.

## AI Policy

AI tools (ChatGPT, Claude, Copilot, etc.) are **allowed and expected**. In your README, include:

- How you used AI during the exercise
- Example prompts you found effective
- Where you adjusted or rejected AI output and why

## Deliverables

When you're done, your fork should include:

1. **Working code** implementing the Stripe payment feature
2. **Database migration(s)** for any new tables
3. **Tests** for core business logic (unit tests preferred; integration tests welcome)
4. **Updated README** covering:
   - Setup and run instructions for your changes
   - Your API design (endpoints, how to use them)
   - Assumptions and tradeoffs
   - What you'd do differently for production / scale
   - AI usage (prompts, adjustments, rejections)

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
curl http://localhost:8080/api/tenants
```

Explore the existing code to understand the patterns, then start building.

Good luck!
