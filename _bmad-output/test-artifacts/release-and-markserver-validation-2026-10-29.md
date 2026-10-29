# Release pipeline and markserver demo validation

Incident recovery started2026-10-28 and continued2026-10-29. Scope: CI-approved source/image/GitOps provenance and the user's authorized markserver demo recovery, including internal MinIO. No unrelated workstation/cluster context was used.

## Source/configuration checks

- `node --test infra/ci/scripts/release-policy.test.cjs` with the existing CI kustomize5.4.3:21 tests passed, zero failures/skips. Tests cover CI-head identity, manual SHA gating, failed/fork/PR/non-main rejection, security/schema/summary failures, bounded pending quality checks, complete digest sets, real Git/Kustomize publication, stale relevant source, older identical-source revisions, simultaneous frontend/backend push races, and a newer source arriving during retry. The actual production overlay wiring is checked too.
- `actionlint` v1.7.12 passed all4 changed CI/CD workflows. `node --check` and `git diff --check` passed. Kustomize renders passed for base, production and ingress-tls.
- The pinned Nginx image's real `nginx -t` passed as UID1001 with network disabled, read-only root filesystem and writable `/tmp` only. The first attempt identified missing default fastcgi temporary paths; all temp paths were corrected before success.
- The live GitHub API read-only release-resolution probe and PR CI evidence are recorded after their final execution below. No claim of CI/CD publication is based on an unexecuted workflow.

## Server observations and repair

- Tailscale was Running/healthy; peer markserver was online and matched the existing SSH alias. Verified remote hostname, kubeadm context and Ready node before touching namespace robomart.
- Inventory/Notification failed because their auth secrets did not exist; both service clients were also absent from Keycloak. Generated/preserved private credentials, created least-privilege roles/client mappings and namespace secrets, and reconciled known existing browser-client audiences without replacing the realm or users.
- Verified newly minted service tokens have canonical issuer, robomart-api audience and exactly the intended service role, without printing tokens. Inventory's minimal nonexistent-order status endpoint returned404 while general admin order listing returned403; Notification general admin listing also returned403.
- Product's legacy image PVC had no matching volume; provisioned a dedicated static Retain PV without deleting/replacing that claim. The MinIO data claim uses a separate Retain PV. Both claims became Bound.
- Elasticsearch referenced a missing PVC. Both pre-existing retained data paths were verified empty; restored the claim/PV linkage and selected the configured9.4.8 image before a new directory format was written. ES then became healthy, and Product health returned UP. No existing data directory was deleted or reset.
- Argo was actually automated/self-healing despite the previous offline deployment assumption. Saved its original state privately and temporarily paused only robomart-production during recovery. Added HPA replica ignores/RespectIgnoreDifferences to prevent competing controllers; monitoring automation was left unchanged.
- The demo MinIO source build first hit a64MiB `/tmp` eviction. Go temporary files moved to the bounded `/build` volume; the eventual MinIO runtime became Ready. The initial parallel bootstrap Job expired while waiting for that cold build; after MinIO was ready, only that newly created failed Job was retried, and bucket/policy initialization completed successfully. PVs/data were retained.
- Product's proxy container initially failed on default Nginx fastcgi temporary paths with a read-only root filesystem. Corrected all paths, hashed the ConfigMap for rollout, and verified2/2 readiness of both Product Pods. No tunnel config or public domain was added.
- Public Python HTTP probes returned Cloudflare403/error1010, so server-to-server token requests were moved to the internal Service with the already verified canonical issuer. Origin and browser-user-agent checks are reported separately.

## Real object-storage smoke

A restricted one-off Job used signed S3 upload, anonymous retrieval through the **actual Gateway /images route**, SHA-256 byte comparison, denied anonymous bucket listing/write, and an EXIT-trap object deletion. The final Job `robomart-storage-smoke-d686e8` completed1/1 and printed PASS. The earlier attempt ran before bucket initialization and failed NoSuchBucket; the next reached successful upload/download but its image lacked cmp. Preconditions were completed and comparison changed to SHA-256 before the successful repeat. No PostgreSQL image row or financial order/payment was created by this smoke.

Remaining final evidence: public browser probes, final service health, source publication/PR CI and restoration of Argo's automated policy after main is aligned. These are not claimed complete until verified.

## Completed origin/browser checks

- Read-only integration against the real GitHub REST API successfully resolved main source `e67142e3b9a69a5e32525433cf193fe285773544` from Backend CI run40476549212 to the exact full-SHA tag and expected registry prefix. An initial probe exposed the legitimate absence of PR SARIF summary checks on default-branch pushes; the resolver now enforces the mandatory Security workflow and any registered summaries, rather than requiring nonexistent main summaries. Regression coverage includes this case.
- Normal browser-user-agent requests to the public customer site, product-list API and Robomart OIDC discovery endpoint all returned200 from the server. The403/error1010 diagnostic was specific to the default Python client signature; no Cloudflare policy/tunnel change was made.
- The object-storage smoke completed with SHA-256 equality through the real Gateway and cleanup. Only the three temporary smoke Jobs and the recovery's own evicted MinIO build Pod were removed after evidence collection; no volume or database data was removed.
- CI/source publication and re-enabling automatic Argo sync remain dependent on the reviewed branch reaching main; the associated PR is the authoritative final CI/merge record.
