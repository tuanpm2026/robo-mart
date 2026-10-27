#!/usr/bin/env python3
"""Inspect or enable KIP-848 only on the label/volume-verified isolated E2E broker."""
import argparse
import json
import re
import subprocess

PROJECT = "robomart-java25-e2e-resume"
IMAGE = "confluentinc/cp-kafka:8.2.4@sha256:58e544adba6a825389ab56dad5f8a1c72d9bbcdcf077e275473f1aff22e2cd76"
CLUSTER = "MkU3OEVBNTcwNTJENDM2Qk"
APPS = {"product-service", "cart-service", "inventory-service", "payment-service",
        "order-service", "notification-service", "api-gateway"}


def run(args):
    result = subprocess.run(args, capture_output=True, text=True, timeout=30, check=True)
    return result.stdout


def broker():
    ids = run(["docker", "ps", "-q", "--filter", f"label=com.docker.compose.project={PROJECT}",
               "--filter", "label=com.docker.compose.service=kafka"]).split()
    if len(ids) != 1:
        raise RuntimeError("Expected exactly one running isolated Kafka broker")
    data = json.loads(run(["docker", "inspect", ids[0]]))[0]
    labels = data["Config"]["Labels"]
    if (labels.get("com.docker.compose.project") != PROJECT
            or labels.get("com.docker.compose.service") != "kafka" or data["Config"]["Image"] != IMAGE):
        raise RuntimeError("Isolated Kafka ownership/image check failed")
    mounts = [m for m in data["Mounts"] if m["Destination"] == "/var/lib/kafka/data"]
    if len(mounts) != 1 or mounts[0]["Type"] != "volume":
        raise RuntimeError("Expected the isolated named Kafka data volume")
    volume = json.loads(run(["docker", "volume", "inspect", mounts[0]["Name"]]))[0]
    if volume.get("Labels", {}).get("com.docker.compose.project") != PROJECT:
        raise RuntimeError("Kafka data volume ownership check failed")
    metadata = run(["docker", "exec", ids[0], "cat", "/var/lib/kafka/data/meta.properties"])
    if f"cluster.id={CLUSTER}" not in metadata.splitlines() or "node.id=1" not in metadata.splitlines():
        raise RuntimeError("Unexpected isolated Kafka cluster/node identity")
    return ids[0]


def features(container):
    value = run(["docker", "exec", container, "kafka-features", "--bootstrap-server", "localhost:9092", "describe"])
    return dict(re.findall(r"Feature:\s*(\S+).*?FinalizedVersionLevel:\s*(\S+)", value))


def require_apps_stopped():
    ids = run(["docker", "ps", "-q", "--filter", f"label=com.docker.compose.project={PROJECT}"]).split()
    for container in ids:
        value = json.loads(run(["docker", "inspect", container]))[0]
        if value["Config"]["Labels"].get("com.docker.compose.service") in APPS:
            raise RuntimeError("Drain isolated application containers before first enabling the consumer feature")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--enable-consumer-protocol", action="store_true")
    args = parser.parse_args()
    container = broker()
    before = features(container)
    group = before.get("group.version")
    if group not in {"0", "1"}:
        raise RuntimeError("Unexpected broker consumer feature level")
    updates = 0
    if args.enable_consumer_protocol and group == "0":
        require_apps_stopped()
        command = ["docker", "exec", container, "kafka-features", "--bootstrap-server", "localhost:9092",
                   "upgrade", "--feature", "group.version=1"]
        run(command + ["--dry-run"])
        run(command)
        updates = 1
    after = features(container)
    if after.get("metadata.version") != before.get("metadata.version"):
        raise RuntimeError("Metadata format changed unexpectedly; no application was started")
    if args.enable_consumer_protocol and after.get("group.version") != "1":
        raise RuntimeError("The isolated broker has not enabled the requested consumer protocol")
    print(f"Isolated Kafka group.version={after['group.version']}, metadata.version={after['metadata.version']} "
          f"({updates} feature updates); no volume formatting or metadata finalization")


if __name__ == "__main__":
    main()
