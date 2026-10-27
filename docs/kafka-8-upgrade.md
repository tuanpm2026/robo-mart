# Confluent 8 and consumer protocol rollout

The repository uses Confluent8.2.4 for Kafka/Schema Registry/Avro serializers. Boot4.1.2 manages kafka-clients4.2.2; Confluent8.2 provides Kafka4.2. Compose broker/registry images are pinned to multi-platform index digests. Generated application Dockerfiles remain independent of the infrastructure images.

## Preserve data before changing binaries

Current Compose/E2E configuration already uses KRaft, node1 and the existing cluster ID/data volume. No ZooKeeper migration, new cluster ID, reformat or volume deletion is part of this change. A deployment still using ZooKeeper must migrate on a supported7.x version before upgrading to8.x; never repoint its old data at a freshly formatted cluster.

Before a real rollout, drain application consumers/pollers, back up their databases and broker/controller data, and record cluster/node/directory IDs, metadata/kraft feature levels, topic IDs/partition counts, committed group offsets, Schema Registry subject IDs/versions/schema content and consumer lag. Upgrade brokers/controllers and Schema Registry before opting applications into new protocol features. Preserve listeners, security settings, replication factors, topic keys and consumer group IDs.

The isolated retained-data upgrade was executed from7.9.10 to8.2.4: all17 topic IDs/partition counts,4 committed partition offsets and6 schema IDs/versions/content remained unchanged. Cluster/node/directory identities remained unchanged. Metadata stayed3.9-IV0 and kraft.version stayed0. The source configurations continue using their existing static quorum; dynamic-quorum migration is separate work.

## Enable KIP-848 deliberately

Application defaults and the K8s ConfigMap retain `KAFKA_GROUP_PROTOCOL=classic`. External production Kafka manifests are not owned by this repository; no live broker or cluster was changed. Upgrade/verify the external cluster before changing this setting.

Inspect feature levels first. For local developer Compose, for example:

```bash
docker compose -f infra/docker/docker-compose.yml exec -T kafka \
  kafka-features --bootstrap-server localhost:9092 describe
docker compose -f infra/docker/docker-compose.yml exec -T kafka \
  kafka-features --bootstrap-server localhost:9092 upgrade --feature group.version=1 --dry-run
```

After verified backups, compatible broker binaries and drained consumers, enable group.version=1, verify the result, then set KAFKA_GROUP_PROTOCOL=consumer for the five Kafka-consuming applications. The equivalent feature command without --dry-run applies that change. The tested8.2.4 broker allowed group.version1 while retaining metadata3.9-IV0; this change never finalizes metadata to the latest version automatically. Stop all members of an existing classic group before its first consumer-protocol restart, preserving its ID and offsets. Existing service Recreate cutovers remain compatible with this empty-group handover.

The new protocol uses broker-controlled heartbeat/session/assignment settings. Do not carry explicit classic-only heartbeat.interval.ms, session.timeout.ms or partition.assignment.strategy overrides into consumer mode, or call enforceRebalance. Existing manual acknowledgement, poll-processing bounds, retries and DLT/archive behavior remain unchanged.

Do not assume old binaries can start after new feature levels are enabled. Plan rollback around the actual supported/finalized features and verified backups; do not force metadata downgrade or format storage as troubleshooting.

## Isolated validation controls

E2E explicitly opts into consumer mode. Before starting applications, configure-kafka.py verifies the exact fixed project/service labels, pinned broker image, named-volume ownership, cluster/node identity and current feature levels. First-time enablement requires all app containers stopped; it runs a dry-run, changes only group.version, and verifies metadata.version is unchanged. Repeated preparation is idempotent. Its default invocation only inspects; --enable-consumer-protocol opts into the guarded update. It cannot target production or arbitrary bootstrap servers.

New disposable Testcontainers brokers enable the same feature and real service consumers use consumer mode. The Events integration suite verifies an empty classic group commits offset1, migrates to the consumer protocol, receives offset1 next (not a replay of offset0), commits offset2, and reports GroupType.CONSUMER. All13 Avro schemas still register, re-register and remain backward compatible against the actual8.2.4 Schema Registry. The test now parses registration responses as JSON because8.x adds response metadata beyond the stable id field.

References: [Confluent8.2 release notes](https://docs.confluent.io/platform/8.2/release-notes/index.html), [Confluent upgrade guide](https://docs.confluent.io/platform/current/installation/upgrade.html), [Kafka consumer rebalance protocol](https://kafka.apache.org/42/operations/consumer-rebalance-protocol/), [Confluent consumer configuration](https://docs.confluent.io/platform/current/clients/consumer.html).
