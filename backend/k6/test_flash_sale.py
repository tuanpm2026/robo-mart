"""Regression tests for evidence that must make the experiment fail, not appear green."""
import argparse
from copy import deepcopy
import unittest
from unittest.mock import patch

from run_flash_sale import Experiment, validate


def evidence(cleaned=False):
    return {
        "orders": [
            {"id": 1, "user_id": "buyer", "status": "CANCELLED" if cleaned else "CONFIRMED",
             "total_amount": 9.99, "orchestrator_owner": "EXTERNAL"},
            {"id": 2, "user_id": "loser", "status": "CANCELLED", "total_amount": 9.99,
             "orchestrator_owner": "EXTERNAL"},
        ],
        "workflows": [
            {"order_id": "1", "state": "CANCELLED" if cleaned else "CHECKOUT_CONFIRMED",
             "reason": "Fixture cleanup" if cleaned else "", "manual_review_at": None},
            {"order_id": "2", "state": "CANCELLED", "reason": "Inventory rejected reservation",
             "manual_review_at": None},
        ],
        "inventory": [{"available_quantity": int(cleaned), "reserved_quantity": 0,
                       "total_quantity": int(cleaned)}],
        "reservations": [{"order_id": "1", "status": "RELEASED" if cleaned else "COMMITTED"},
                         {"order_id": "2", "status": "RELEASED"}],
        "movements": [{"order_id": "1", "type": "RESERVE", "quantity": 1},
                      {"order_id": "1", "type": "COMMIT", "quantity": 1}]
                     + ([{"order_id": "1", "type": "RELEASE", "quantity": 1}] if cleaned else []),
        "payments": [{"order_id": "1", "status": "REFUNDED" if cleaned else "COMPLETED",
                      "amount": 9.99, "refunded_amount": 9.99 if cleaned else 0}],
        "providerOperations": [{"operation_key": "charge:1", "request_order_id": "1",
                                "status": "COMPLETED", "amount": 9.99}]
                              + ([{"operation_key": "refund:txn:1", "request_order_id": None,
                                   "status": "REFUNDED", "amount": 9.99}] if cleaned else []),
    }


class EvidenceTest(unittest.TestCase):
    def test_complete_sale_and_cleanup(self):
        validate(evidence(), ["buyer", "loser"])
        validate(evidence(True), ["buyer", "loser"], True)

    def test_missing_or_invalid_evidence_fails_closed(self):
        cases = {
            "missing customer": lambda e: e["orders"].pop(),
            "extra order after replay": lambda e: e["orders"].append(deepcopy(e["orders"][0])),
            "same customer twice": lambda e: e["orders"][1].update(user_id="buyer"),
            "two winners": lambda e: e["orders"][1].update(status="CONFIRMED"),
            "pending outcome": lambda e: e["orders"][1].update(status="PENDING"),
            "wrong owner": lambda e: e["orders"][0].update(orchestrator_owner="EMBEDDED"),
            "wrong price": lambda e: e["orders"][0].update(total_amount=0.01),
            "payment decline disguised as stock": lambda e: e["workflows"][1].update(reason="Payment declined"),
            "missing workflow": lambda e: e["workflows"].pop(),
            "manual review": lambda e: e["workflows"][0].update(manual_review_at="now"),
            "oversell": lambda e: e["inventory"][0].update(available_quantity=-1),
            "stock still reserved": lambda e: e["inventory"][0].update(reserved_quantity=1),
            "missing application payment": lambda e: e["payments"].clear(),
            "payment for loser": lambda e: e["payments"][0].update(order_id="2"),
            "extra charge": lambda e: e["providerOperations"].append(
                dict(e["providerOperations"][0], operation_key="charge:another")),
            "missing provider evidence": lambda e: e["providerOperations"].clear(),
            "wrong provider amount": lambda e: e["providerOperations"][0].update(amount=19.98),
            "missing reservation": lambda e: e["reservations"].pop(),
            "duplicate stock movement": lambda e: e["movements"].append(deepcopy(e["movements"][0])),
            "missing commit": lambda e: e["movements"].pop(),
            "stock movement for loser": lambda e: e["movements"][0].update(order_id="2"),
        }
        for name, corrupt in cases.items():
            with self.subTest(name=name):
                value = evidence()
                corrupt(value)
                with self.assertRaises(AssertionError):
                    validate(value, ["buyer", "loser"])

    def test_cleanup_requires_money_and_stock_to_be_returned(self):
        cases = {
            "missing refund": lambda e: e["providerOperations"].pop(),
            "unsettled provider refund": lambda e: e["providerOperations"][1].update(status="PENDING"),
            "partial provider refund": lambda e: e["providerOperations"][1].update(amount=5),
            "partial application refund": lambda e: e["payments"][0].update(refunded_amount=5),
            "missing release": lambda e: e["movements"].pop(),
            "stock not returned": lambda e: e["inventory"][0].update(available_quantity=0),
        }
        for name, corrupt in cases.items():
            with self.subTest(name=name):
                value = evidence(True)
                corrupt(value)
                with self.assertRaises(AssertionError):
                    validate(value, ["buyer", "loser"], True)

    @patch("run_flash_sale.command")
    def test_cleanup_restores_payment_when_load_process_failed(self, command):
        experiment = Experiment(argparse.Namespace(customers=2, payment_outage=True))
        experiment.payment = "owned-payment"
        experiment.payment_stopped = True
        self.assertEqual(experiment.cleanup(), [])
        command.assert_called_once_with("docker", "start", "owned-payment")
        self.assertFalse(experiment.payment_stopped)

    @patch("run_flash_sale.command", side_effect=RuntimeError("restart failed"))
    def test_failed_restore_must_fail_the_run(self, _command):
        experiment = Experiment(argparse.Namespace(customers=2, payment_outage=True))
        experiment.payment = "owned-payment"
        experiment.payment_stopped = True
        self.assertEqual(experiment.cleanup(), ["restart failed"])
        self.assertEqual(experiment.report["cleanup"]["status"], "failed")


if __name__ == "__main__":
    unittest.main()
