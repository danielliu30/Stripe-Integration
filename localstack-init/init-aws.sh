#!/bin/bash
set -euo pipefail

echo "Initializing LocalStack resources..."

if ! awslocal s3api head-bucket --bucket takehome-files >/dev/null 2>&1; then
  awslocal s3api create-bucket --bucket takehome-files >/dev/null
fi

DLQ_URL=$(awslocal sqs create-queue --queue-name takehome-jobs-dlq --query QueueUrl --output text)
DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url "$DLQ_URL" \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' \
  --output text)
QUEUE_URL=$(awslocal sqs create-queue --queue-name takehome-jobs --query QueueUrl --output text)
REDRIVE_POLICY=$(printf \
  '{"deadLetterTargetArn":"%s","maxReceiveCount":"5"}' \
  "$DLQ_ARN")
ATTRIBUTES=$(REDRIVE_POLICY="$REDRIVE_POLICY" python3 -c \
  'import json, os; print(json.dumps({"RedrivePolicy": os.environ["REDRIVE_POLICY"]}))')
awslocal sqs set-queue-attributes \
  --queue-url "$QUEUE_URL" \
  --attributes "$ATTRIBUTES"

echo "LocalStack initialization complete."
