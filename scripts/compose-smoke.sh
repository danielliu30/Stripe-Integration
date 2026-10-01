#!/usr/bin/env bash
set -euo pipefail

export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-takehome-smoke-$$}"
INFRA_HOST="${COMPOSE_INFRA_HOST:-localhost}"
API_URL="${SMOKE_API_URL:-http://localhost:8080}"
LOG_DIR="${SMOKE_LOG_DIR:-build/compose-smoke}"
API_PID=""
WORKER_PID=""

cleanup() {
  local exit_code=$?
  if [[ -n "$API_PID" ]]; then kill "$API_PID" 2>/dev/null || true; fi
  if [[ -n "$WORKER_PID" ]]; then kill "$WORKER_PID" 2>/dev/null || true; fi
  docker compose logs --no-color >"$LOG_DIR/compose.log" 2>&1 || true
  docker compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  exit "$exit_code"
}
trap cleanup EXIT

fail() {
  echo "Smoke test failed: $*" >&2
  exit 1
}

wait_for_container() {
  local service=$1
  local container
  for _ in $(seq 1 60); do
    container=$(docker compose ps -q "$service")
    if [[ -n "$container" ]] && [[ $(docker inspect -f '{{.State.Health.Status}}' "$container") == "healthy" ]]; then
      return
    fi
    sleep 2
  done
  fail "$service did not become healthy"
}

wait_for_http() {
  local url=$1
  for _ in $(seq 1 60); do
    if curl --silent --fail "$url" >/dev/null; then return; fi
    sleep 1
  done
  fail "$url did not become available"
}

json_field() {
  local field=$1
  python3 -c 'import json,sys; print(json.load(sys.stdin)[sys.argv[1]])' "$field"
}

mysql_value() {
  docker compose exec -T mysql mysql --silent --skip-column-names -uroot -ppassword takehome -e "$1"
}

queue_count() {
  local queue_url=$1
  docker compose exec -T localstack awslocal sqs get-queue-attributes \
    --queue-url "$queue_url" \
    --attribute-names ApproximateNumberOfMessages \
    --query 'Attributes.ApproximateNumberOfMessages' \
    --output text
}

mkdir -p "$LOG_DIR"
docker compose up -d --force-recreate
wait_for_container mysql
wait_for_container localstack

docker compose exec -T localstack awslocal s3api head-bucket --bucket takehome-files >/dev/null
QUEUES=$(docker compose exec -T localstack awslocal sqs list-queues --query 'QueueUrls' --output text)
[[ "$QUEUES" == *takehome-jobs* ]] || fail "main queue was not initialized"
[[ "$QUEUES" == *takehome-jobs-dlq* ]] || fail "dead-letter queue was not initialized"
MAIN_QUEUE_URL="http://$INFRA_HOST:4566/000000000000/takehome-jobs"
LOCAL_MAIN_QUEUE_URL="http://localhost:4566/000000000000/takehome-jobs"
LOCAL_DLQ_URL="http://localhost:4566/000000000000/takehome-jobs-dlq"
REDRIVE=$(docker compose exec -T localstack awslocal sqs get-queue-attributes \
  --queue-url "$LOCAL_MAIN_QUEUE_URL" \
  --attribute-names RedrivePolicy \
  --query 'Attributes.RedrivePolicy' \
  --output text)
[[ "$REDRIVE" == *takehome-jobs-dlq* && "$REDRIVE" == *'"maxReceiveCount":"5"'* ]] ||
  fail "main queue redrive policy is incorrect"

./gradlew bootJar --no-daemon
JAR_PATH=""
for candidate in build/libs/*.jar; do
  if [[ "$candidate" != *-plain.jar ]]; then JAR_PATH=$candidate; break; fi
done
[[ -n "$JAR_PATH" ]] || fail "boot jar was not created"

export SPRING_DATASOURCE_URL="jdbc:mysql://$INFRA_HOST:3306/takehome"
export AWS_S3_ENDPOINT="http://$INFRA_HOST:4566"
export AWS_SQS_ENDPOINT="http://$INFRA_HOST:4566"
export AWS_SQS_QUEUE_URL="$MAIN_QUEUE_URL"
export STRIPE_SECRET_KEY="sk_test_compose_smoke_invalid"
export STRIPE_WEBHOOK_SECRET="whsec_compose_smoke"
export PAYMENT_RECOVERY_INITIAL_DELAY_SECONDS=1
export PAYMENT_RECOVERY_DISPATCH_INTERVAL_MS=250
export PAYMENT_RECOVERY_STALE_CLAIM_SECONDS=5
export PAYMENT_RECOVERY_PUBLICATION_RETRY_DELAY_SECONDS=1
export WORKER_RETRY_BASE_DELAY_SECONDS=1
export WORKER_RETRY_MAX_DELAY_SECONDS=2
export WORKER_RETRY_JITTER_RATIO=0

java -jar "$JAR_PATH" --server.port=8080 --worker.enabled=false >"$LOG_DIR/api.log" 2>&1 &
API_PID=$!
java -jar "$JAR_PATH" --server.port=8081 --worker.enabled=true >"$LOG_DIR/worker.log" 2>&1 &
WORKER_PID=$!
wait_for_http "$API_URL/api/checkout/return"

INVALID_LOGIN_STATUS=$(curl --silent --output "$LOG_DIR/invalid-login.json" --write-out '%{http_code}' \
  -X POST "$API_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice.johnson@email.com","password":"wrong"}')
[[ "$INVALID_LOGIN_STATUS" == "401" ]] || fail "wrong password returned HTTP $INVALID_LOGIN_STATUS"
[[ $(json_field message <"$LOG_DIR/invalid-login.json") == "Invalid credentials" ]] ||
  fail "wrong password returned unexpected error message"
LOGIN=$(curl --silent --fail -X POST "$API_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice.johnson@email.com","password":"password"}')
TOKEN=$(printf '%s' "$LOGIN" | json_field token)
[[ -n "$TOKEN" ]] || fail "tenant login did not return a token"
CARDS=$(curl --silent --fail "$API_URL/api/cards" -H "Authorization: Bearer $TOKEN")
[[ $(printf '%s' "$CARDS" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["content"]))') == "0" ]] ||
  fail "fresh tenant unexpectedly has cards"
CHECKOUT_STATUS=$(curl --silent --output "$LOG_DIR/checkout-error.json" --write-out '%{http_code}' \
  -X POST "$API_URL/api/cards/checkout-session" \
  -H "Authorization: Bearer $TOKEN")
[[ "$CHECKOUT_STATUS" == "502" ]] || fail "Stripe checkout failure returned HTTP $CHECKOUT_STATUS"
[[ $(mysql_value "SELECT COUNT(*) FROM tenants WHERE id=1 AND stripe_customer_id IS NOT NULL;") == "0" ]] ||
  fail "failed Stripe customer creation changed tenant state"
MISSING_HEADER_STATUS=$(curl --silent --output "$LOG_DIR/missing-header.json" --write-out '%{http_code}' \
  -X POST "$API_URL/api/rent-charges/1/pay" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"cardId":1}')
[[ "$MISSING_HEADER_STATUS" == "400" ]] ||
  fail "missing Idempotency-Key returned HTTP $MISSING_HEADER_STATUS"
[[ $(json_field message <"$LOG_DIR/missing-header.json") == "Missing required header: Idempotency-Key" ]] ||
  fail "missing Idempotency-Key returned unexpected error message"
WEBHOOK_PAYLOAD='{"id":"evt_compose_incomplete","object":"event","type":"payment_intent.succeeded","data":{"object":{"id":"pi_incomplete","object":"payment_intent","status":"succeeded"}}}'
WEBHOOK_TIMESTAMP=$(date +%s)
WEBHOOK_SIGNATURE=$(PAYLOAD="$WEBHOOK_PAYLOAD" TIMESTAMP="$WEBHOOK_TIMESTAMP" python3 -c \
  'import hashlib,hmac,os; value=os.environ["TIMESTAMP"]+"."+os.environ["PAYLOAD"]; print(hmac.new(b"whsec_compose_smoke", value.encode(), hashlib.sha256).hexdigest())')
PAYMENTS_BEFORE_WEBHOOK=$(mysql_value "SELECT COUNT(*) FROM payments;")
WEBHOOK_STATUS=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  -X POST "$API_URL/api/webhooks/stripe" \
  -H 'Content-Type: application/json' \
  -H "Stripe-Signature: t=$WEBHOOK_TIMESTAMP,v1=$WEBHOOK_SIGNATURE" \
  -d "$WEBHOOK_PAYLOAD")
[[ "$WEBHOOK_STATUS" == "200" ]] || fail "signed incomplete webhook returned HTTP $WEBHOOK_STATUS"
[[ $(mysql_value "SELECT COUNT(*) FROM payments;") == "$PAYMENTS_BEFORE_WEBHOOK" ]] ||
  fail "signed incomplete webhook mutated payments"

docker compose exec -T mysql mysql -uroot -ppassword takehome -e \
  "UPDATE tenants SET stripe_customer_id='cus_compose_smoke' WHERE id=1;
   INSERT INTO cards (tenant_id,stripe_payment_method_id,brand,last4,exp_month,exp_year)
   VALUES (1,'pm_compose_smoke','visa','4242',12,2030);"

PAYMENT_BODY="$LOG_DIR/payment.json"
PAYMENT_STATUS=$(curl --silent --output "$PAYMENT_BODY" --write-out '%{http_code}' \
  -X POST "$API_URL/api/rent-charges/1/pay" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: compose-smoke-payment' \
  -d '{"cardId":1}')
[[ "$PAYMENT_STATUS" == "202" ]] || fail "uncertain payment returned HTTP $PAYMENT_STATUS"
[[ $(json_field status <"$PAYMENT_BODY") == "INITIATED" ]] || fail "uncertain payment was not INITIATED"
PAYMENT_ID=$(json_field id <"$PAYMENT_BODY")

REPLAY_BODY="$LOG_DIR/replay.json"
REPLAY_STATUS=$(curl --silent --output "$REPLAY_BODY" --write-out '%{http_code}' \
  -X POST "$API_URL/api/rent-charges/1/pay" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: compose-smoke-payment' \
  -d '{"cardId":1}')
[[ "$REPLAY_STATUS" == "202" ]] || fail "pending replay returned HTTP $REPLAY_STATUS"
[[ $(json_field id <"$REPLAY_BODY") == "$PAYMENT_ID" ]] || fail "pending replay returned another payment"
[[ $(mysql_value "SELECT COUNT(*) FROM payments WHERE idempotency_key='compose-smoke-payment';") == "1" ]] ||
  fail "idempotent request created multiple payments"

for _ in $(seq 1 45); do
  RECOVERY_STATUS=$(mysql_value "SELECT status FROM payment_recoveries WHERE payment_id=$PAYMENT_ID;")
  if [[ "$RECOVERY_STATUS" == "PUBLISHED" ]]; then break; fi
  sleep 1
done
[[ "$RECOVERY_STATUS" == "PUBLISHED" ]] || fail "recovery was not published"

for _ in $(seq 1 45); do
  DLQ_COUNT=$(queue_count "$LOCAL_DLQ_URL")
  if [[ "$DLQ_COUNT" == "1" ]]; then break; fi
  sleep 1
done
[[ "$DLQ_COUNT" == "1" ]] || fail "failed recovery did not reach the DLQ"
[[ $(queue_count "$LOCAL_MAIN_QUEUE_URL") == "0" ]] || fail "main queue still contains recovery work"

PM_LOGIN=$(curl --silent --fail -X POST "$API_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@greenfieldproperties.com","password":"password"}')
PM_TOKEN=$(printf '%s' "$PM_LOGIN" | json_field token)
GENERATE_STATUS=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  -X POST "$API_URL/api/rent-charges/generate" \
  -H "Authorization: Bearer $PM_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"dueDate":"2099-11-01"}')
[[ "$GENERATE_STATUS" == "202" ]] || fail "rent-charge generation returned HTTP $GENERATE_STATUS"
for _ in $(seq 1 20); do
  GENERATED=$(mysql_value "SELECT COUNT(*) FROM rent_charges WHERE due_date='2099-11-01';")
  if [[ "$GENERATED" == "2" ]]; then break; fi
  sleep 1
done
[[ "$GENERATED" == "2" ]] || fail "worker did not process rent-charge generation"

echo "Fresh-stack compose smoke test passed."
