#!/bin/bash
echo "Initializing LocalStack resources..."

# Create S3 bucket for file storage
awslocal s3 mb s3://takehome-files

# Create SQS queue for background jobs
awslocal sqs create-queue --queue-name takehome-jobs
awslocal sqs create-queue --queue-name takehome-jobs-dlq

echo "LocalStack initialization complete."
