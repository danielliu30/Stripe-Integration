#!/bin/bash
echo "Initializing LocalStack resources..."

# Create S3 bucket for file storage
awslocal s3 mb s3://takehome-files

# Create the DLQ first so the worker queue can redrive after five failed deliveries
DLQ_URL=$(awslocal sqs create-queue --queue-name takehome-jobs-dlq --query QueueUrl --output text)
DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url "$DLQ_URL" \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' \
  --output text)
awslocal sqs create-queue \
  --queue-name takehome-jobs \
  --attributes "RedrivePolicy={\"deadLetterTargetArn\":\"$DLQ_ARN\",\"maxReceiveCount\":\"5\"}"

echo "LocalStack initialization complete."
