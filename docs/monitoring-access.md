# Monitoring access on markserver

Kafka UI and Prometheus are operator tools protected by the `Robo-Mart Operations`
Cloudflare Access application. Both public hostnames require the single operator's
email policy and the configured Cloudflare identity provider. No Everyone, Bypass,
or service-token allow policy is configured.

| Public hostname | Tunnel origin | Kubernetes exposure |
| --- | --- | --- |
| `kafka-ui.tuanpm.xyz` | `http://10.98.61.120:8080` | `monitoring/kafka-ui`, ClusterIP |
| `prometheus.tuanpm.xyz` | `http://10.106.247.154:9090` | `monitoring/prometheus`, ClusterIP |

These addresses belong to the existing retained Services. Recreating a Service
may change its ClusterIP and requires a matching tunnel update. ClusterIP does not
authenticate cluster workloads; node administration and Kubernetes access remain
trusted operator boundaries. The former Kafka UI NodePort `30808` is removed to
prevent clients from reaching the application through that external listener.

## Required rollout order

1. Create the Access application and verify both destinations and the exact
   operator email allow rule. Test anonymous requests and authorized browser login.
2. Update only the two hostname blocks in the active host-managed
   `/etc/cloudflared/config.yml`. Preserve all other routes and the final 404 rule.
   Kafka UI must use its ClusterIP before the NodePort is removed. Configure
   `originRequest.access.required: true`, the actual team name, and the application
   audience for both routes. See the official
   [origin Access settings](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/configure-tunnels/origin-parameters/#access).
3. Save a private backup, validate with
   `cloudflared --config <candidate> tunnel ingress validate`, install the reviewed
   candidate and restart cloudflared. Host configuration requires sudo. Validate
   service health and authenticated browser access before proceeding.
4. Deploy the committed `infra/k8s/monitoring/kafka-ui/service.yml` through the
   monitoring ArgoCD Application. Its self-heal policy must not retain the previous
   NodePort as desired state. Do not delete/recreate the Service or its ClusterIP.
5. Verify anonymous requests to both hostnames redirect to Access, authorized
   browser requests work, and a direct request to the former `30808` NodePort fails.
   Prometheus scraping inside the cluster must continue independently of Access.

On 2026-11-01, [PR 51](https://github.com/tuanpm2026/robo-mart/pull/51) completed
the Service rollout after the tunnel configuration was activated. Anonymous
requests returned 302 to the team's `cloudflareaccess.com` login, authenticated
browser reloads opened both tools, and the former NodePort refused connections.
Prometheus reported seven healthy Spring Boot targets. See the
[execution and incident report](../_bmad-output/test-artifacts/monitoring-access-validation-2026-11-01.md).

## Persistent storage prerequisites

The generic monitoring manifest requests PVCs and expects a default StorageClass.
markserver has no default provisioner. Its retained claims are bound to dedicated
static local PVs with an empty storage class and node affinity to `markserver`:

| Claim | PV | Host directory | Owner |
| --- | --- | --- | --- |
| `prometheus-data` | `markserver-monitoring-prometheus` | `/home/tuanpm/.robomart-data/monitoring/prometheus` | `65534:65534` |
| `tempo-data` | `markserver-monitoring-tempo` | `/home/tuanpm/.robomart-data/monitoring/tempo` | `10001:10001` |
| `grafana-data` | `markserver-monitoring-grafana` | `/home/tuanpm/.robomart-data/monitoring/grafana` | `472:472` |

All three PVs use `Retain`. The exact provisioned manifest and preservation evidence
are stored privately on the node under `~/.robomart-ops/2026-11-01-access/`.
Do not recreate a bound claim or replace a nonempty target directory as a retry.
Grafana additionally requires the external `grafana-admin` Secret; preserve its
existing identity and database when converting a legacy literal environment value.

Before changing an older installation, inspect the full Argo diff. A source sync
can also apply previously pending storage/security changes. Provision PVCs and
back up live data before replacing any `emptyDir` Pod. When a live Deployment still
has `RollingUpdate` fields, replace its entire strategy with the already-declared
`Recreate` strategy before retrying SSA; changing only `type` can leave an invalid
combination. A literal environment `value` must likewise be removed when switching
that same entry to `valueFrom`.

## Recovery

Keep the Access policies enabled during origin troubleshooting. Restore a known
working tunnel configuration if its restart fails. Prefer repairing the ClusterIP
route over reopening a NodePort. Do not remove databases, volumes, topics or
monitoring data as part of Access configuration.
