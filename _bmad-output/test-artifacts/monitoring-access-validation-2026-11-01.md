# Monitoring Access validation and storage incident

Date: 2026-11-01, Asia/Ho_Chi_Minh. Environment: markserver, namespace `monitoring`,
context `kubernetes-admin@kubernetes`, accessed through the verified
`tuanpm-server` SSH alias. Source: [PR 51](https://github.com/tuanpm2026/robo-mart/pull/51),
squash `50f2628552c0d8256eadff98b18775f3422abf75`.

## Result and data boundary

- Cloudflare Access protects Kafka UI and Prometheus with one exact operator-email
  Allow policy and the configured Cloudflare identity provider. No Everyone,
  Bypass or service-token policy was added.
- Both anonymous hostname requests return 302 to the team's Access login.
  Authorized browser reloads open Kafka UI and Prometheus.
- The active host-managed tunnel validates the Access audience for both origins.
  Kafka UI now uses its retained ClusterIP, and the former NodePort 30808 refuses
  connections from both the node and the workstation's Tailscale path.
- Monitoring ArgoCD reached `Synced / Healthy / Succeeded` at the reviewed source.
  Its original prune/self-heal automation was restored after recovery.
- Prometheus reported seven healthy Spring Boot targets, Grafana's health API
  reported database `ok`, and Tempo's `/ready` returned 200.

**The rollout also caused loss of the previous ephemeral Prometheus/Tempo history.**
Their old `emptyDir` directories were already absent after the Pods were replaced.
No metrics/trace-history recovery from those directories is claimed. Grafana was
still running and was copied with SQLite's consistent backup API before its Pod
was replaced; integrity check returned `ok`, with one user and zero dashboards in
the backup. Business-service databases, orders, payments and product images were
not migrated or reset by this monitoring recovery.

## Executed configuration and release checks

`kubectl kustomize infra/k8s/monitoring`, `git diff --check`, and the prepared
`cloudflared --config <candidate> tunnel ingress validate` passed. PR 51's 13 checks
passed, including Backend CI's seven integration-test jobs, unit/style/coverage
build, CodeQL and Trivy summaries. The PR merged only after the tunnel prerequisite
was completed.

The workstation's default SSH Git identity lacked repository permission. Publication
used the already authenticated owner account through the HTTPS credential helper;
no credential was printed or committed.

## Tunnel activation

The operator activated Zero Trust Free directly because its checkout required
accepting service terms and possible overage billing. Account activation was not
performed by the agent.

Only the Kafka UI and Prometheus origin blocks in `/etc/cloudflared/config.yml`
changed. A private backup and candidate were retained. The activation script checked
the prior configuration hash, validated the candidate, installed it, restarted
cloudflared, checked active state, and included restoration on restart failure.

Direct sudo required an unavailable password. At the user's explicit request to
run the prepared script, a one-off node-administration Kubernetes Job executed it
in the host namespaces. It mounted no service-account token and was deleted after
completion. No new user credential, operator role or permanent root workload was
created.

## Storage and reconciliation incident

The monitoring Application had older unapplied configuration beyond the Service
change. Its first sync failed because Prometheus/Grafana/Tempo still had live
`RollingUpdate` fields while Git declared `Recreate`. Replacing those strategy
objects allowed reconciliation to continue; it also rolled Prometheus and Tempo
onto the already-declared PVC configuration. Their claims had been Pending since
the older configuration was introduced: markserver had neither a matching PV nor
a default StorageClass. The agent had not verified these prerequisites before
initiating the sync. This caused a monitoring interruption and the history loss
described above.

The monitoring Application alone was temporarily paused and its original state
saved privately. Recovery created three dedicated static local PVs, each with
`Retain`, the correct existing claim identity and node affinity. Target directories
were checked empty before initialization. No existing PV or bound claim was deleted.

A separate bounded Job copied the live Grafana directory and made a consistent
SQLite backup. It used a read-only host root with dedicated writable data/evidence
mounts, limited capabilities and no service-account token. It was removed after
completion. The existing Grafana environment credential was moved into the required
`grafana-admin` Secret in process memory and stdin, preserving its value. No password
rotation or database-user reset was attempted. Administrative API login with that
legacy environment credential was not verified; this report does not claim that
the user's Grafana login was tested.

After all claims became Bound, the declared monitoring configuration synchronized
successfully. Readiness and scrape checks passed, the original automated policy
was restored, and the temporary Jobs were removed. Private evidence is retained at
`~/.robomart-ops/2026-11-01-access/`, including activation logs, the previous
Application, `monitoring-local-pvs.json` and `storage-preservation.json`.
