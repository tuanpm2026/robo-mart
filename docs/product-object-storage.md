# Product image object storage

Product stores images through AWS SDK v2 in S3-compatible storage. PostgreSQL continues to own image metadata/order. JPEG/PNG/WebP signature checks and the 5 MiB limit remain enforced. Keys are immutable `{productId}/{UUID}.{jpg|png|webp}`; uploads use conditional creation and SHA-256 metadata. Upload rollback removes the new object; committed deletion removes the object only after the SQL transaction commits. Cleanup failure is logged for operator reconciliation rather than changing an already committed response.

New rows contain stable public bucket/CDN URLs. Provide anonymous object GET through a dedicated public image bucket or CDN; never allow anonymous listing or writes. Existing `/images/{productId}/{filename}` URLs remain unchanged in SQL and redirect to the same key after migration. The endpoint checks existence; it does not expose the old filesystem. Frontend request/response shapes are unchanged.

## Configuration and local fixtures

Set `S3_REGION`, `S3_BUCKET`, `S3_PUBLIC_BASE_URL` (absolute HTTP(S), no credentials/query/fragment). Optionally set `S3_ENDPOINT` and `S3_PATH_STYLE=true` for compatible providers. Leave both `S3_ACCESS_KEY` and `S3_SECRET_KEY` blank for the SDK default credential chain/workload IAM, or supply both through secrets. The application never creates buckets or changes bucket policies. Give its identity only GetObject/PutObject/DeleteObject on the image prefix; migration also needs GetObject/PutObject. Request/connect/socket deadlines are bounded.

Normal Compose's core profile builds MinIO from pinned patched source, starts it on loopback port 9002, and runs a bounded one-shot AWS CLI bucket bootstrap. Its committed credentials are local development fixtures. E2E uses a separate volume, credentials and loopback port 19002. Bootstrap grants anonymous `s3:GetObject` only. No production storage is deployed by these files. The community MinIO repository was archived in April 2026; the October 2025 security release requires building source. This pinned fixture is for local tests; provision a maintained managed S3-compatible service for production. Source/license are identified in the Dockerfile/image (AGPL-3.0-only).

Kubernetes consumes an operator-provided `product-s3-secret` (template only). Supply the bucket, endpoint/IAM and public URL before rollout. Existing image PVCs are preserved and mounted read-only at `/migration/images`; no cluster apply is performed by this change. Network policies currently restrict ingress only, so they do not require a new S3 egress rule.

## Existing-volume migration

1. Back up the image volume and database. Drain all old filesystem writers before copying; do not run mixed old/new upload implementations during migration. Provision the target bucket, permissions and public delivery first. Keep the original volume and a rollback backup.
2. Build/install Product and its dependencies. Extract the Boot application into an isolated working directory using `java -Djarmode=tools -jar product-service-0.0.1-SNAPSHOT.jar extract --destination extracted`. The resulting thin JAR manifest references `lib/`; keep that layout intact.
3. Mount the legacy image directory **read-only** and run the plain CLI from the extracted directory:

   ```bash
   java -cp product-service-0.0.1-SNAPSHOT.jar com.robomart.product.service.ImageMigrationCommand --source /migration/images
   # Set the target S3 environment and review the dry-run before applying:
   java -cp product-service-0.0.1-SNAPSHOT.jar com.robomart.product.service.ImageMigrationCommand --source /migration/images --apply
   ```

   The default dry-run is source-only: no credentials or network. It prints eligible relative keys, sizes and SHA-256 values; symlinks and unknown filenames are skipped. Invalid image sizes/MIME-extension mismatches fail. Apply conditionally creates missing objects, downloads and verifies bytes, and accepts existing objects only if content matches. Conflicts fail without overwriting or deleting either copy. Repeating apply is idempotent. CLI output is evidence, not a command to delete originals.
4. Verify every eligible key was reported `VERIFIED`, inspect skipped entries, and sample old URLs through the Gateway and new public URLs anonymously. Missing-object redirects return 404. Roll out Product with its S3 secret only after this check. No database URL rewrite or Flyway migration is needed.
5. Keep the source volume/backup through the rollback window. Do not delete it as routine cleanup. During rollback, restore the old application and make a separate deliberate plan for objects uploaded since cutover; those new SQL URLs still reference S3 and must remain available.

A failed post-commit delete may leave an unreferenced object. Reconcile against committed `product_images` after accounting for in-flight uploads and retained backups; this change does not install an unsafe automatic orphan deleter.

References: [MinIO security release](https://github.com/minio/minio/releases/tag/RELEASE.2025-10-15T17-29-55Z), [AWS SDK v2 S3 examples](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/java_s3_code_examples.html).
