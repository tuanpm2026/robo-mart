"""Business and measurement regressions for the concurrent checkout experiment."""
from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from run_concurrent_orders import correlate_traces, main, observations, percentile, summarize, validate_concurrent


def evidence(cleaned=False):
    ids = ['1', '2']
    return {
        'orders': [{'id': int(i), 'user_id': 'user-' + i, 'status': 'CANCELLED' if cleaned else 'CONFIRMED',
                    'orchestrator_owner': 'EXTERNAL', 'total_amount': 9.99} for i in ids],
        'inventory': [{'available_quantity': 2 if cleaned else 0, 'reserved_quantity': 0,
                       'total_quantity': 2 if cleaned else 0}],
        'workflows': [{'order_id': i, 'state': 'CANCELLED' if cleaned else 'CHECKOUT_CONFIRMED',
                       'manual_review_at': None} for i in ids],
        'reservations': [{'order_id': i, 'status': 'RELEASED' if cleaned else 'COMMITTED'} for i in ids],
        'movements': [{'order_id': i, 'type': t, 'quantity': 1} for i in ids
                      for t in ['RESERVE', 'COMMIT'] + (['RELEASE'] if cleaned else [])],
        'payments': [{'order_id': i, 'status': 'REFUNDED' if cleaned else 'COMPLETED', 'amount': 9.99,
                      'refunded_amount': 9.99 if cleaned else 0, 'currency': 'USD',
                      'transaction_id': 'txn-' + i} for i in ids],
        'providerOperations': [{'request_order_id': i, 'operation_key': 'charge:' + i,
                                'status': 'COMPLETED', 'currency': 'USD', 'amount': 9.99} for i in ids]
                              + ([{'request_identity': 'txn-' + i, 'operation_key': 'refund:txn-' + i,
                                   'status': 'REFUNDED', 'amount': 9.99} for i in ids] if cleaned else []),
    }


class ConcurrentEvidenceTest(unittest.TestCase):
    def test_all_orders_charged_and_fully_compensated(self):
        validate_concurrent(evidence(), ['user-1', 'user-2'])
        validate_concurrent(evidence(True), ['user-1', 'user-2'], True)

    def test_corrupt_business_evidence_fails(self):
        cases = {
            'missing order': lambda e: e['orders'].pop(),
            'duplicate customer': lambda e: e['orders'][1].update(user_id='user-1'),
            'duplicate order ID': lambda e: e['orders'][1].update(id=1),
            'pending order': lambda e: e['orders'][1].update(status='PENDING'),
            'cancelled despite sufficient stock': lambda e: e['orders'][1].update(status='CANCELLED'),
            'wrong price': lambda e: e['orders'][0].update(total_amount=1),
            'oversell': lambda e: e['inventory'][0].update(available_quantity=-1),
            'missing reservation': lambda e: e['reservations'].pop(),
            'duplicate reserve': lambda e: e['movements'].append(deepcopy(e['movements'][0])),
            'wrong reserved quantity': lambda e: e['movements'][0].update(quantity=2),
            'missing payment': lambda e: e['payments'].pop(),
            'duplicate payment': lambda e: e['payments'].append(deepcopy(e['payments'][0])),
            'payment currency mismatch': lambda e: e['payments'][0].update(currency='EUR'),
            'reused provider transaction': lambda e: e['payments'][1].update(transaction_id='txn-1'),
            'missing charge': lambda e: e['providerOperations'].pop(),
            'duplicate charge': lambda e: e['providerOperations'].append(deepcopy(e['providerOperations'][0])),
            'wrong charge binding': lambda e: e['providerOperations'][1].update(request_order_id='1'),
            'manual review': lambda e: e['workflows'][1].update(manual_review_at='now'),
        }
        for name, corrupt in cases.items():
            with self.subTest(name=name):
                value = evidence()
                corrupt(value)
                with self.assertRaises(AssertionError):
                    validate_concurrent(value, ['user-1', 'user-2'])

    def test_cleanup_must_refund_each_original_transaction(self):
        cases = {
            'wrong transaction refunded twice': lambda e: e['providerOperations'][3].update(request_identity='txn-1'),
            'missing provider refund': lambda e: e['providerOperations'].pop(),
            'partial provider refund': lambda e: e['providerOperations'][2].update(amount=1),
            'partial application refund': lambda e: e['payments'][1].update(refunded_amount=1),
            'missing release': lambda e: e['movements'].pop(),
            'missing stock': lambda e: e['inventory'][0].update(available_quantity=1),
        }
        for name, corrupt in cases.items():
            with self.subTest(name=name):
                value = evidence(True)
                corrupt(value)
                with self.assertRaises(AssertionError):
                    validate_concurrent(value, ['user-1', 'user-2'], True)


class MeasurementTest(unittest.TestCase):
    @patch('run_concurrent_orders.rows')
    @patch('run_concurrent_orders.command', return_value='')
    @patch('run_concurrent_orders.owned_container', return_value={'Id': 'owned-order'})
    def test_trace_audit_must_match_both_order_and_trace(self, _container, _command, rows):
        trace_id = 'a' * 32
        rows.return_value = [{'entity_id': '1', 'trace_id': trace_id, 'created_at': 'now'},
                             {'entity_id': '2', 'trace_id': trace_id, 'created_at': 'now'}]
        result = correlate_traces([{'id': 1, 'traceId': trace_id, 'submittedAt': 1000}], 'postgres')
        self.assertEqual(result['auditedOrdersCorrelated'], 1)

    @patch('run_concurrent_orders.command')
    @patch('run_concurrent_orders.owned_container', return_value={'Id': 'owned-order'})
    def test_trace_evidence_keeps_correlation_metadata_only(self, _container, command):
        trace_id = 'a' * 32
        command.return_value = json.dumps({'traceId': trace_id, '@timestamp': 'now',
                                          'log': {'logger': 'OrderService'}, 'message': 'SECRET'})
        result = correlate_traces([{'traceId': trace_id, 'submittedAt': 1000}])
        self.assertEqual(result['correlatedTraceIds'], 1)
        self.assertNotIn('SECRET', json.dumps(result))

    def test_percentiles_and_throughput_use_verified_business_observations(self):
        data = [dict(id=1, vu=1, submittedAt=1000, acceptedAt=1100, confirmedAt=2000, attempts=1),
                dict(id=2, vu=2, submittedAt=1005, acceptedAt=1205, confirmedAt=3000, attempts=2)]
        log = '\n'.join('LOAD_ORDER ' + json.dumps(x) for x in data)
        checked = observations(log, [1, 2])
        result = summarize(checked)
        self.assertEqual(result['confirmedOrdersPerSecond'], 1)
        self.assertEqual(result['observedBurstSeconds'], 2)
        self.assertEqual(result['acceptanceMs']['p95'], 195)
        self.assertEqual(result['submissionSpreadMs'], 5)
        self.assertEqual(result['httpAttempts'], 3)
        self.assertEqual(percentile([300, 100, 200], .5), 200)

    def test_no_survivor_bias_from_missing_or_duplicate_samples(self):
        value = dict(id=1, vu=1, submittedAt=1000, acceptedAt=1100, confirmedAt=2000, attempts=1)
        line = 'LOAD_ORDER ' + json.dumps(value)
        for log in [line, line + '\n' + line]:
            with self.subTest(log=log), self.assertRaises(AssertionError):
                observations(log, [1, 2])

    def test_invalid_timing_must_fail(self):
        value = dict(id=1, vu=1, submittedAt=1000, acceptedAt=900, confirmedAt=2000, attempts=1)
        with self.assertRaises(AssertionError):
            observations('LOAD_ORDER ' + json.dumps(value), [1])
        with self.assertRaises(AssertionError):
            percentile([], .95)


class SuiteLifecycleTest(unittest.TestCase):
    def run_suite(self, fail_slo, restore_error=False):
        class FakeExperiment:
            def __init__(self, args):
                self.report = {'customers': args.customers, 'correctness': 'passed',
                               'performance': 'failed' if fail_slo else 'passed'}

            def preflight(self):
                pass

            def provision(self):
                pass

            def run(self, _directory):
                if fail_slo:
                    raise AssertionError('SLO failed')

            def cleanup(self):
                self.report['cleanup'] = {'status': 'passed', 'errors': []}
                return []

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'result.json'
            with patch('sys.argv', ['runner', '--levels', '2', '--worker-interval-ms', '25', '--output', str(output)]), \
                    patch('run_concurrent_orders.ConcurrentExperiment', FakeExperiment), \
                    patch('run_concurrent_orders.owned_container', return_value={'Config': {'Env': ['SAGA_WORKER_INTERVAL_MS=500']}}), \
                    patch('run_concurrent_orders.configure_interval', side_effect=[None, RuntimeError('restore failed')] if restore_error else None) as configure, \
                    patch('builtins.print'):
                code = main()
                self.assertEqual([call.args[0] for call in configure.call_args_list], [25, 500])
            return code, json.loads(output.read_text())

    def test_slo_miss_remains_failure_and_original_worker_is_restored(self):
        code, result = self.run_suite(True)
        self.assertEqual(code, 1)
        self.assertEqual(result['status'], 'failed')
        self.assertTrue(result['workerRestored'])
        self.assertEqual(result['runs'][0]['correctness'], 'passed')

    def test_failed_restore_fails_an_otherwise_successful_suite(self):
        code, result = self.run_suite(False, True)
        self.assertEqual(code, 1)
        self.assertFalse(result['workerRestored'])
        self.assertEqual(result['runs'][0]['status'], 'passed')


if __name__ == '__main__':
    unittest.main()
