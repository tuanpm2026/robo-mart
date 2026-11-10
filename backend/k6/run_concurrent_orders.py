#!/usr/bin/env python3
"""Measure bounded checkout bursts with authoritative business evidence and an enforced SLO."""
import argparse
from collections import Counter
from datetime import datetime, timezone
from decimal import Decimal
import json
import hashlib
import math
import os
import re
from pathlib import Path
import subprocess
import tempfile
import uuid

from run_flash_sale import (PASSWORD, PROJECT, ROOT, Experiment, await_valid,
                            capture, command, login, owned_container, query, require, rows)


def validate_concurrent(evidence, user_ids, cleaned=False):
    orders = evidence['orders']
    n = len(user_ids)
    require(n > 0 and len(orders) == n and Counter(o['user_id'] for o in orders) == Counter(user_ids),
            'Expected exactly one order per fixture customer')
    ids = {str(o['id']) for o in orders}
    require(len(ids) == n and all(o['orchestrator_owner'] == 'EXTERNAL'
            and o['status'] == ('CANCELLED' if cleaned else 'CONFIRMED')
            and Decimal(str(o['total_amount'])) == Decimal('9.99') for o in orders),
            'Missing/duplicate/unconfirmed orders or wrong pricing/owner')
    require(evidence['inventory'] == [{'available_quantity': n if cleaned else 0,
                                     'reserved_quantity': 0, 'total_quantity': n if cleaned else 0}],
            'Incorrect inventory balance or incomplete stock restoration')
    expected_state = 'CANCELLED' if cleaned else 'CHECKOUT_CONFIRMED'
    require(Counter((str(w['order_id']), w['state'], w['manual_review_at']) for w in evidence['workflows'])
            == Counter((i, expected_state, None) for i in ids), 'Incomplete or duplicate Saga workflows')
    require(Counter((str(r['order_id']), r['status']) for r in evidence['reservations'])
            == Counter((i, 'RELEASED' if cleaned else 'COMMITTED') for i in ids),
            'Missing, extra or unresolved reservations')
    types = ['RESERVE', 'COMMIT'] + (['RELEASE'] if cleaned else [])
    require(Counter((str(m['order_id']), m['type'], m['quantity']) for m in evidence['movements'])
            == Counter((i, t, 1) for i in ids for t in types), 'Missing/duplicate/incorrect stock movements')
    payments = evidence['payments']
    require(Counter(str(p['order_id']) for p in payments) == Counter(ids), 'Missing or duplicate payments')
    require(all(p['status'] == ('REFUNDED' if cleaned else 'COMPLETED') and p['currency'] == 'USD'
                and Decimal(str(p['amount'])) == Decimal('9.99')
                and Decimal(str(p['refunded_amount'])) == (Decimal('9.99') if cleaned else 0)
                and p['transaction_id'] for p in payments), 'Incorrect application payment/refund')
    transactions = {p['transaction_id'] for p in payments}
    require(len(transactions) == n, 'Provider transaction reused across orders')
    charges = [p for p in evidence['providerOperations'] if p['operation_key'].startswith('charge:')]
    refunds = [p for p in evidence['providerOperations'] if p['operation_key'].startswith('refund:')]
    require(len(evidence['providerOperations']) == n * (2 if cleaned else 1)
            and Counter((str(p['request_order_id']), p['operation_key'], p['status'], p['currency'],
                         Decimal(str(p['amount']))) for p in charges)
            == Counter((i, 'charge:' + i, 'COMPLETED', 'USD', Decimal('9.99')) for i in ids),
            'Missing/duplicate provider charge or charge not bound to its original order')
    require(Counter((p['request_identity'], p['status'], Decimal(str(p['amount']))) for p in refunds)
            == (Counter((t, 'REFUNDED', Decimal('9.99')) for t in transactions) if cleaned else Counter()),
            'Missing/duplicate provider refund or refund bound to the wrong transaction')


def percentile(values, fraction):
    require(bool(values), 'Cannot compute a percentile without samples')
    values = sorted(values)
    position = (len(values) - 1) * fraction
    lower = math.floor(position)
    upper = math.ceil(position)
    return values[lower] + (values[upper] - values[lower]) * (position - lower)


def observations(log, expected_ids):
    data = [json.loads(line[len('LOAD_ORDER '):]) for line in log.splitlines() if line.startswith('LOAD_ORDER ')]
    require(Counter(str(x['id']) for x in data) == Counter(str(i) for i in expected_ids),
            'Timing observations are missing, duplicated or do not match the verified orders')
    require(Counter(x['vu'] for x in data) == Counter(range(1, len(data) + 1)), 'Missing/duplicate customer timing')
    require(all(x['submittedAt'] <= x['acceptedAt'] <= x['confirmedAt'] and 1 <= x['attempts'] <= 3 for x in data),
            'Invalid checkout timing sequence')
    return data


def summarize(data):
    span_ms = max(x['confirmedAt'] for x in data) - min(x['submittedAt'] for x in data)
    require(span_ms > 0, 'Invalid measurement span')
    result = {'samples': len(data), 'observedBurstSeconds': span_ms / 1000,
              'confirmedOrdersPerSecond': len(data) * 1000 / span_ms,
              'submissionSpreadMs': max(x['submittedAt'] for x in data) - min(x['submittedAt'] for x in data),
              'httpAttempts': sum(x['attempts'] for x in data)}
    for key, start, end in [('acceptanceMs', 'submittedAt', 'acceptedAt'),
                            ('afterAcceptanceMs', 'acceptedAt', 'confirmedAt'),
                            ('endToEndMs', 'submittedAt', 'confirmedAt')]:
        values = [x[end] - x[start] for x in data]
        result[key] = {'p50': percentile(values, .5), 'p95': percentile(values, .95),
                       'p99': percentile(values, .99), 'max': max(values)}
    return result


def timeline(postgres, product_id):
    orders = rows(postgres, 'order_db',
                  "SELECT o.id, EXTRACT(epoch FROM o.created_at)*1000 AS created_ms, "
                  "EXTRACT(epoch FROM min(h.changed_at))*1000 AS confirmed_ms FROM orders o "
                  "JOIN order_status_history h ON h.order_id=o.id AND h.status='CONFIRMED' "
                  f"WHERE EXISTS (SELECT 1 FROM order_items i WHERE i.order_id=o.id AND i.product_id={int(product_id)}) "
                  'GROUP BY o.id ORDER BY o.id')
    ids = ','.join("'" + str(int(o['id'])) + "'" for o in orders) or "'no-orders'"
    workflows = rows(postgres, 'saga_db',
                     'SELECT w.order_id, EXTRACT(epoch FROM w.created_at)*1000 AS received_ms, '
                     "EXTRACT(epoch FROM min(s.dispatched_at) FILTER (WHERE s.operation_key LIKE '%:reserve'))*1000 AS reserve_ms, "
                     "EXTRACT(epoch FROM min(s.dispatched_at) FILTER (WHERE s.operation_key LIKE '%:charge'))*1000 AS charge_ms, "
                     "EXTRACT(epoch FROM min(s.dispatched_at) FILTER (WHERE s.operation_key LIKE '%:CONFIRM'))*1000 AS confirm_dispatch_ms "
                     'FROM saga_instances w LEFT JOIN saga_steps s ON s.saga_id=w.saga_id '
                     f'WHERE w.order_id IN ({ids}) GROUP BY w.order_id,w.created_at ORDER BY w.order_id')
    by_id = {str(w['order_id']): w for w in workflows}
    joined = [dict(o, **by_id[str(o['id'])]) for o in orders]
    require(all(all(x.get(k) is not None for k in ('created_ms', 'confirmed_ms', 'received_ms',
                                                  'reserve_ms', 'charge_ms', 'confirm_dispatch_ms')) for x in joined),
            'Missing persisted timing evidence')
    return {'source': 'Read-only timestamps from one PostgreSQL server; dispatch times are intent persistence, not RPC duration',
            'orders': joined,
            'p95Ms': {name: percentile([x[end] - x[start] for x in joined], .95) for name, start, end in [
                ('orderToSagaInbox', 'created_ms', 'received_ms'),
                ('inboxToReserveDispatch', 'received_ms', 'reserve_ms'),
                ('reserveToChargeDispatch', 'reserve_ms', 'charge_ms'),
                ('chargeToConfirmDispatch', 'charge_ms', 'confirm_dispatch_ms'),
                ('serverConfirmation', 'created_ms', 'confirmed_ms')]}}


def correlate_traces(data, postgres=None):
    trace_ids = {x.get('traceId') for x in data if re.fullmatch(r'[a-f0-9]{32}', x.get('traceId') or '')}
    container = owned_container('order-service')['Id']
    since = str(math.floor(min(x['submittedAt'] for x in data) / 1000))
    matched = {}
    for line in command('docker', 'logs', '--since', since, container).splitlines():
        try:
            record = json.loads(line)
        except json.JSONDecodeError:
            continue
        if record.get('traceId') in trace_ids:
            # Persist only correlation metadata; log messages can contain credentials/PII.
            matched[record['traceId']] = {'traceId': record['traceId'], 'timestamp': record.get('@timestamp'),
                                          'logger': record.get('log', {}).get('logger')}
    audited = []
    if postgres:
        ids = ','.join("'" + str(int(x['id'])) + "'" for x in data)
        audits = rows(postgres, 'order_db', 'SELECT entity_id,trace_id,created_at FROM audit_log '
                      f"WHERE entity_type='Order' AND action='CREATE' AND entity_id IN ({ids})")
        expected = {(str(x['id']), x.get('traceId')) for x in data}
        audited = [x for x in audits if (x['entity_id'], x['trace_id']) in expected]
    return {'kind': 'Response trace IDs correlated with Order CREATE audit and structured logs; not exported span timings',
            'responseTraceIds': len(trace_ids), 'correlatedTraceIds': len(matched),
            'examples': list(matched.values())[:5],
            'auditedOrdersCorrelated': len({x['entity_id'] for x in audited}), 'auditExamples': audited[:5]}


class ConcurrentExperiment(Experiment):
    def __init__(self, args):
        super().__init__(args)
        self.run_id = 'load-' + uuid.uuid4().hex
        self.report.update(runId=self.run_id, scope='Isolated local closed burst; mock provider; enforced 3s p95 after acceptance')

    def stock_quantity(self):
        return self.args.customers

    def preflight(self):
        super().preflight()
        paths = ['backend/k6/run_concurrent_orders.py', 'backend/k6/scripts/concurrent-orders.js',
                 'backend/k6/run_flash_sale.py']
        self.report['testSourceSHA256'] = {p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in paths}
        env = dict(e.split('=', 1) for e in owned_container('checkout-saga-service')['Config']['Env'] if '=' in e)
        self.report['observedWorkerIntervalMs'] = int(env.get('SAGA_WORKER_INTERVAL_MS', '500'))
        require(self.report['observedWorkerIntervalMs'] == self.args.worker_interval_ms,
                'Running worker configuration does not match the requested experiment')

    def cleanup_timeout(self):
        return max(180, self.args.timeout)

    def evidence_validator(self, evidence, user_ids, cleaned=False):
        validate_concurrent(evidence, user_ids, cleaned)

    def run(self, directory):
        customers = [{'token': login(u['username'], PASSWORD)} for u in self.users]
        fixture = directory / 'fixture.json'
        fixture.write_text(json.dumps({'runId': self.run_id, 'productId': self.product_id,
                                      'customers': customers, 'timeoutSeconds': self.args.timeout}))
        fixture.chmod(0o600)
        env = {k: v for k, v in os.environ.items() if not k.startswith('K6_')}
        env.update(CONCURRENT_ORDERS_FIXTURE=str(fixture), CONCURRENT_ORDERS_SUMMARY=str(directory / 'summary.json'))
        with (directory / 'k6.log').open('w') as log:
            self.process = subprocess.Popen(['k6', 'run', '--quiet', '--log-format', 'raw',
                                            str(ROOT / 'backend/k6/scripts/concurrent-orders.js')],
                                           env=env, stdout=log, stderr=subprocess.STDOUT)
            code = self.process.wait(timeout=self.args.timeout + 90)
        self.report['k6ExitCode'] = code
        self.report['k6Summary'] = json.loads((directory / 'summary.json').read_text())
        log = (directory / 'k6.log').read_text()
        # Preserve observations without persisting any credential fixture.
        self.report['afterLoad'] = capture(self.postgres, self.product_id)
        self.report['beforeCleanup'] = await_valid(self.postgres, self.product_id,
                                                   [u['id'] for u in self.users], 60,
                                                   validator=validate_concurrent)
        self.report['correctness'] = 'passed'
        self.report['observations'] = observations(log, [o['id'] for o in self.report['beforeCleanup']['orders']])
        self.report['measurement'] = summarize(self.report['observations'])
        self.report['serverTimeline'] = timeline(self.postgres, self.product_id)
        self.report['traceCorrelation'] = correlate_traces(self.report['observations'], self.postgres)
        metrics = self.report['k6Summary']['metrics']
        thresholds = {f'{name}: {expression}': value['ok'] for name, metric in metrics.items()
                      for expression, value in metric.get('thresholds', {}).items()}
        require(bool(thresholds), 'k6 did not evaluate thresholds')
        self.report['thresholds'] = thresholds
        self.report['performance'] = 'passed' if thresholds.get('saga_completion_time: p(95)<3000') else 'failed'
        print(f"{self.args.customers} customers: " + json.dumps(self.report['measurement']), flush=True)
        require(code == 0 and all(thresholds.values()), f'k6 thresholds failed (exit {code}); see measured evidence')


def configure_interval(interval, log_path):
    owned_container('checkout-saga-service')
    postgres = owned_container('postgres')['Id']
    require(query(postgres, 'saga_db', 'SELECT count(*) FROM saga_instances WHERE due_at IS NOT NULL') == 0,
            'Wait for all fixture workflows to settle before changing worker configuration')
    env = dict(os.environ, E2E_SAGA_WORKER_INTERVAL_MS=str(interval))
    with log_path.open('w') as log:
        result = subprocess.run(['docker', 'compose', '--env-file', '/dev/null', '--project-name', PROJECT,
                                 '--file', str(ROOT / 'infra/docker/docker-compose.e2e.yml'), 'up', '-d',
                                 '--no-deps', '--wait', '--wait-timeout', '180', 'checkout-saga-service'],
                                env=env, stdout=log, stderr=subprocess.STDOUT, timeout=220)
    require(result.returncode == 0, 'Isolated Saga configuration failed; see configuration log')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--levels', type=int, nargs='+', default=[25, 50, 100])
    parser.add_argument('--timeout', type=int, default=180)
    parser.add_argument('--worker-interval-ms', type=int, choices=[25, 500], default=500)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if not all(2 <= n <= 100 for n in args.levels) or not 60 <= args.timeout <= 600:
        parser.error('levels must be 2..100 and timeout 60..600 seconds')
    suite_id = 'concurrent-' + uuid.uuid4().hex
    output = args.output or ROOT / 'backend/k6/results' / (suite_id + '.json')
    require(not output.exists(), 'Refusing to overwrite existing evidence')
    output.parent.mkdir(parents=True, exist_ok=True)
    suite = {'startedAt': datetime.now(timezone.utc).isoformat(), 'workerIntervalMs': args.worker_interval_ms,
             'runs': [], 'status': 'failed', 'slo': 'p95 after acceptance <3000ms; never relaxed'}
    original = None
    changed = False
    try:
        container = owned_container('checkout-saga-service')
        env = dict(e.split('=', 1) for e in container['Config']['Env'] if '=' in e)
        original = int(env.get('SAGA_WORKER_INTERVAL_MS', '500'))
        if original != args.worker_interval_ms:
            changed = True  # Register restoration before the mutation can fail.
            configure_interval(args.worker_interval_ms, output.with_suffix('.configure.log'))
        for n in args.levels:
            experiment = ConcurrentExperiment(argparse.Namespace(customers=n, timeout=args.timeout, payment_outage=False,
                                                                 worker_interval_ms=args.worker_interval_ms))
            with tempfile.TemporaryDirectory(prefix='robomart-load-') as temporary:
                try:
                    experiment.preflight()
                    experiment.report['workerIntervalMs'] = args.worker_interval_ms
                    print(f'Provisioning {n} disposable customers and {n} stock units...', flush=True)
                    experiment.provision()
                    experiment.run(Path(temporary))
                    experiment.report['status'] = 'passed'
                except (Exception, KeyboardInterrupt) as error:
                    experiment.report.update(status='failed', error=str(error) or 'Interrupted')
                    if isinstance(error, KeyboardInterrupt):
                        raise
                finally:
                    if experiment.cleanup():
                        experiment.report['status'] = 'failed'
                    suite['runs'].append(experiment.report)
                    output.write_text(json.dumps(suite, indent=2) + '\n')
            # Explore subsequent levels after an honest SLO miss, never after a business/cleanup failure.
            if experiment.report.get('correctness') != 'passed' or experiment.report['cleanup']['status'] != 'passed':
                break
        suite['status'] = 'passed' if len(suite['runs']) == len(args.levels) and all(
            r['status'] == 'passed' for r in suite['runs']) else 'failed'
    except (Exception, KeyboardInterrupt) as error:
        suite['status'] = 'failed'
        suite['error'] = str(error) or 'Interrupted'
    finally:
        if changed:
            try:
                configure_interval(original, output.with_suffix('.restore.log'))
                suite['workerRestored'] = True
            except Exception as error:
                suite.update(status='failed', restoreError=str(error), workerRestored=False)
        suite['finishedAt'] = datetime.now(timezone.utc).isoformat()
        output.write_text(json.dumps(suite, indent=2) + '\n')
    print(f"{suite['status'].upper()}: {output}", flush=True)
    return 0 if suite['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
