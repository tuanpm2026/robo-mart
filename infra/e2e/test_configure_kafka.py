"""Negative ownership tests for the isolated feature updater; no Docker calls are made."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("configure_kafka", Path(__file__).with_name("configure-kafka.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class Fixture:
    def __init__(self):
        self.calls = []
        self.group = "0"
        self.active_app = False
        self.metadata_feature = "3.9-IV0"
        self.simulate_metadata_change = False
        self.container = {"Config": {"Image": module.IMAGE, "Labels": {
            "com.docker.compose.project": module.PROJECT, "com.docker.compose.service": "kafka"}},
            "Mounts": [{"Destination": "/var/lib/kafka/data", "Type": "volume", "Name": "owned-data"}]}
        self.volume = {"Labels": {"com.docker.compose.project": module.PROJECT}}
        self.metadata = f"cluster.id={module.CLUSTER}\nnode.id=1\n"

    def run(self, args):
        self.calls.append(args)
        if args[:3] == ["docker", "ps", "-q"]:
            return "broker\n" if "label=com.docker.compose.service=kafka" in args else (
                "broker\napp\n" if self.active_app else "broker\n")
        if args[:2] == ["docker", "inspect"]:
            data = self.container if args[2] == "broker" else {"Config": {"Labels": {
                "com.docker.compose.project": module.PROJECT, "com.docker.compose.service": "product-service"}}}
            return json.dumps([data])
        if args[:3] == ["docker", "volume", "inspect"]:
            return json.dumps([self.volume])
        if "meta.properties" in args[-1]:
            return self.metadata
        if args[-1] == "describe":
            return f"Feature: group.version FinalizedVersionLevel: {self.group}\n" + (
                f"Feature: metadata.version FinalizedVersionLevel: {self.metadata_feature}\n")
        if "upgrade" in args:
            if "--dry-run" not in args:
                self.group = "1"
                if self.simulate_metadata_change:
                    self.metadata_feature = "4.2-IV1"
            return "group.version can be upgraded to 1."
        raise AssertionError(f"Unexpected fixture command: {args}")


class KafkaFeatureGuardTest(unittest.TestCase):
    def invoke(self, fixture, enable=True):
        args = ["configure-kafka.py"] + (["--enable-consumer-protocol"] if enable else [])
        with patch.object(module, "run", side_effect=fixture.run), patch.object(sys, "argv", args):
            with contextlib.redirect_stdout(io.StringIO()):
                module.main()

    def test_refuses_wrong_container_volume_image_or_cluster_before_any_mutation(self):
        changes = [
            lambda f: f.container["Config"]["Labels"].update({"com.docker.compose.project": "other"}),
            lambda f: f.container["Config"]["Labels"].update({"com.docker.compose.service": "other"}),
            lambda f: f.container["Config"].update({"Image": "unverified:latest"}),
            lambda f: f.volume["Labels"].update({"com.docker.compose.project": "other"}),
            lambda f: f.container["Mounts"][0].update({"Type": "bind"}),
            lambda f: setattr(f, "metadata", "cluster.id=other\nnode.id=1\n"),
        ]
        for change in changes:
            with self.subTest(change=change):
                fixture = Fixture()
                change(fixture)
                with self.assertRaises(RuntimeError):
                    self.invoke(fixture)
                self.assertFalse(any("upgrade" in args for args in fixture.calls))

    def test_refuses_first_upgrade_while_application_is_running(self):
        fixture = Fixture()
        fixture.active_app = True
        with self.assertRaisesRegex(RuntimeError, "Drain"):
            self.invoke(fixture)
        self.assertEqual(fixture.group, "0")
        self.assertFalse(any("upgrade" in args for args in fixture.calls))

    def test_read_only_default_and_enabled_repeat_do_not_upgrade(self):
        fixture = Fixture()
        self.invoke(fixture, enable=False)
        self.assertEqual(fixture.group, "0")
        fixture.group = "1"
        fixture.active_app = True
        self.invoke(fixture)
        self.assertFalse(any("upgrade" in args for args in fixture.calls))

    def test_detects_unexpected_metadata_change_before_starting_apps(self):
        fixture = Fixture()
        fixture.simulate_metadata_change = True
        with self.assertRaisesRegex(RuntimeError, "Metadata format changed"):
            self.invoke(fixture)

    def test_guarded_upgrade_is_dry_run_then_group_only_without_metadata_finalization(self):
        fixture = Fixture()
        self.invoke(fixture)
        changes = [args for args in fixture.calls if "upgrade" in args]
        self.assertEqual(len(changes), 2)
        self.assertIn("--dry-run", changes[0])
        self.assertNotIn("--dry-run", changes[1])
        self.assertTrue(all("group.version=1" in args for args in changes))
        self.assertEqual(fixture.group, "1")
        self.assertFalse(any("metadata.version=" in word for args in changes for word in args))


if __name__ == "__main__":
    unittest.main()
