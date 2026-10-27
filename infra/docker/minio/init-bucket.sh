#!/bin/sh
# Local fixture only: fail closed on unexpected bucket names; anonymous GET only, never listing/writes.
set -eu
case "$S3_BUCKET" in ''|*[!a-z0-9.-]*) echo 'Invalid fixture bucket name' >&2; exit 2;; esac
export AWS_EC2_METADATA_DISABLED=true AWS_REGION=us-east-1
aws --endpoint-url http://minio:9000 s3api head-bucket --bucket "$S3_BUCKET" 2>/dev/null ||
  aws --endpoint-url http://minio:9000 s3api create-bucket --bucket "$S3_BUCKET" >/dev/null
policy="{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":[\"s3:GetObject\"],\"Resource\":[\"arn:aws:s3:::$S3_BUCKET/*\"]}]}"
aws --endpoint-url http://minio:9000 s3api put-bucket-policy --bucket "$S3_BUCKET" --policy "$policy"
echo 'Local image bucket ready (anonymous object GET only).'
