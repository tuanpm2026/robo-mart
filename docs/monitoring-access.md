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

On 2026-11-01, the Access application was created and both anonymous requests
returned 302 to the team's `cloudflareaccess.com` login. The authorized browser
opened Kafka UI and Prometheus; the query
`count(up{job="spring-boot-services"} == 1)` returned 7. Origin configuration and
the Service change require the rollout steps above; these observations alone do
not prove that the old NodePort has been closed.

## Recovery

Keep the Access policies enabled during origin troubleshooting. Restore a known
working tunnel configuration if its restart fails. Prefer repairing the ClusterIP
route over reopening a NodePort. Do not remove databases, volumes, topics or
monitoring data as part of Access configuration.
