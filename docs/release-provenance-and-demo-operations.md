# Release provenance and markserver demo operations

The CD workflows resolve one immutable source SHA before any image publication. Application source, OCI revision label, full-SHA tag, uploaded image record and GitOps digest all refer to that same approved commit.

## Release gates and publication

- Automatic releases accept only successful **push** CI for this repository's `main` and the expected CI workflow. Fork, PR, failed, unfinished and off-main runs are rejected.
- Manual dispatch must run the workflow from `main`. Supply a full lowercase40-character main commit with successful matching push CI. An empty input resolves the current main HEAD and applies the same gate; a bot GitOps commit skipped by CI will therefore require choosing the actual approved source SHA explicitly.
- Security Scan must finish successfully for the same SHA. Registered path-filtered Schema Compatibility runs must pass too. Registered CodeQL/Trivy summary failures block publication independently of workflow success. Default-branch SARIF uploads may not create PR summary checks, so their absence is not treated as a failed analysis; the Security Scan workflow remains mandatory. The bounded quality wait fails closed after approximately4minutes.
- The trusted release helper is checked out from `github.workflow_sha` and copied outside the source checkout. Each image then checks out the resolved SHA explicitly, including manual historical builds. Mutable `latest` aliases and implicit short-SHA metadata inference are removed.
- Each matrix job records exactly its service name, approved source, canonical registry image and returned SHA-256 digest. GitOps downloads records only from this CD run, requires the complete expected image set, and rejects missing, extra, wrong-source, foreign-image or malformed-digest records.
- GitOps pins the returned **digests**, renders the overlay, and writes a `backend-release.json` or `frontend-release.json` audit record. Each publication starts from freshly fetched main. Only the component's image entries and audit record change. A non-fast-forward race retries from fresh main up to4times, preserving the other component's changes. Authentication/other push errors are not retried as races; force push is never used.
- A newer relevant source/policy change or a newer published revision makes an older run `superseded`, with no overlay update. Relevant source paths include the owning app tree, Docker/policy inputs and its CI/CD workflow. Unrelated frontend/backend changes and GitOps commits do not invalidate an otherwise identical component source.

For a manual release, select the successful **main push** CI commit, then dispatch the corresponding workflow with that full SHA. An intentionally old-code rollback is a separate reviewed GitOps digest revert; it is not a bypass of the stale-build guard. Preserve S3 availability and migration compatibility during rollback.

The existing CI workflows execute `node --test infra/ci/scripts/release-policy.test.cjs` with Node22 and kustomize5.4.3. The tests use real temporary Git remotes and real Kustomize rendering to exercise publication races, stale builds and idempotence, in addition to API-policy and manifest validation.

## markserver demo topology

This production-named overlay targets the user's single-node **demo** server `markserver`. It is not a portable cloud storage deployment. The verified SSH alias is `tuanpm-server`; its Tailscale address was matched to the `markserver` peer before access. Remote commands use the server's `kubernetes-admin@kubernetes` context; the workstation's unrelated Kubernetes contexts are not used.

The user chose internal MinIO for this demo. The patched MinIO source revision and archive checksum match the Compose fixture. An unprivileged init container builds it with a pinned Go image; runtime uses a pinned Alpine image, UID/GID1001, a read-only root filesystem, no service-account token and no exposed console. Cold source builds need network access and may take substantial time. Go cache and temporary compilation files use `/build` (8GiB emptyDir), not the small runtime `/tmp`; compiler memory is bounded separately. This avoids the observed64MiB temporary-volume eviction.

Local PVs are fixed to `markserver` and use `Retain`:

| Claim | Host directory | Purpose |
| --- | --- | --- |
| robomart-minio-demo | /home/tuanpm/.robomart-data/minio | Dedicated image objects |
| product-images | /home/tuanpm/.robomart-data/product-images | Preserved legacy migration mount |

Create the directories before scheduling. Provision `robomart-minio-demo-auth` and `product-s3-secret` through Kubernetes secrets, never Git/chat/logs. Existing credentials must be retained on repeat; the initial demo credentials were generated randomly on the server. The dedicated single-bucket demo uses that private MinIO identity. For a real production service, use a maintained storage provider and bucket-scoped application IAM instead of this demo root identity/source-build setup.

The bucket initializer waits for MinIO, creates only the dedicated `robomart-images` bucket if absent and grants anonymous `s3:GetObject` only. No anonymous listing/writes are granted. Argo sync waves place storage/config first, MinIO health next, and the initializer before normal application rollout. During imperative recovery, start/wait for MinIO before retrying a bootstrap Job whose earlier cold-start deadline expired. Never delete volumes/data to retry the Job.

## Public images without tunnel changes

The existing `/images/**` Gateway route remains the public read entry point. A small non-root Product sidecar serves that prefix from MinIO and forwards other Product requests unchanged to localhost8081. The Product Service targets the sidecar's8088 port; its existing allowed-caller NetworkPolicy also permits8088. Only GET/HEAD image requests are accepted. SDK writes go directly to MinIO, not through this public proxy.

`S3_ENDPOINT=http://robomart-minio-demo:9000`, path-style access and `S3_PUBLIC_BASE_URL=https://api.tuanpm.xyz/images` preserve HTTPS/browser-compatible URLs without changing Cloudflare tunnel configuration. The ConfigMap generator hashes the Nginx configuration so updates trigger a Pod rollout. Every Nginx temporary path is under its writable `/tmp`, including fastcgi/uwsgi/scgi paths required even when those features are unused. PostgreSQL image rows and old files are not rewritten/deleted by this setup. See [the explicit migration procedure](product-object-storage.md) for nonempty legacy data.

## Identity and recovery boundaries

Provision Inventory/Notification confidential service-account clients with only their matching service realm role and `robomart-api` audience. Preserve existing users, client IDs and secrets; do not import the fixture realm over live data. Add the audience mapper to existing known browser clients. A canonical `KC_HOSTNAME=https://auth.tuanpm.xyz` keeps issuer claims stable for internal and external requests. Internal service token requests use the Kubernetes Keycloak Service, avoiding Cloudflare browser-signature filtering while retaining the canonical issuer and JWT verification.

The Elasticsearch recovery on2026-10-28 was specific: its PVC was missing, retained PV metadata existed, and both old local directories were independently verified empty with no running ES process. The claim was restored to the existing Retain PV and the configured9.4.8 runtime was selected before writing a new data directory. This does not authorize jumping a nonempty8.17 cluster to9.x; nonempty data requires the supported upgrade/snapshot procedure. Existing PostgreSQL, Kafka, Redis and retained storage were preserved.

Argo automatic sync was temporarily paused during recovery so old desired state could not overwrite the repair. The original Application was saved under the server user's private `.robomart-ops/2026-10-28/` directory. The new Application configuration ignores only HPA-owned backend replica fields and respects those ignores during sync, preventing self-heal/autoscaler fights. Restore the original automated policy only after this configuration is on main and live prerequisites/health have been verified.

Cloudflare1010 indicates a browser-signature rejection; it must not be confused with a failed origin. Internal signed service requests should not depend on that public edge. External browser verification is reported separately from origin health. [Cloudflare diagnostic reference](https://developers.cloudflare.com/support/troubleshooting/http-status-codes/cloudflare-1xxx-errors/error-1010/).

References: [GitHub workflow_run](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_run), [workflow/job concurrency](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency). GitOps uses optimistic, non-forced retries rather than assuming ordering between the independent frontend/backend workflows.

The final recovery probes with a normal browser user agent returned200 for the customer site, public product API and OIDC discovery. The signed/anonymous object smoke also passed through the actual Gateway, including SHA-256 byte equality and private-write enforcement. Automated Python probes with their library default user agent still receive Cloudflare1010; this edge behavior was preserved rather than disabling the existing browser-integrity policy.
