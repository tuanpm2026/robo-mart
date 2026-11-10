const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { test } = require('node:test');
const vm = require('node:vm');

async function execute(posts, gets = []) {
    const calls = [], metrics = {}, logs = [];
    let now = 0;
    class Metric {
        constructor(name) { this.name = name; metrics[name] = []; }
        add(value) { metrics[this.name].push(value); }
    }
    function response(value) {
        return { status: value.status, json() {
            if (value.malformed) throw new Error('Malformed response');
            return { data: value.data };
        } };
    }
    const http = {
        post(url, body, options) {
            calls.push({ url, body, key: options.headers['Idempotency-Key'] });
            assert.ok(posts.length, 'Unexpected checkout request');
            now += 100;
            return response(posts.shift());
        },
        get() { now += 50; return response(gets.shift() || { status: 503 }); },
    };
    const context = vm.createContext({
        __VU: 1, __ENV: { CONCURRENT_ORDERS_FIXTURE: 'fixture', CONCURRENT_ORDERS_SUMMARY: 'summary' },
        open: () => JSON.stringify({ customers: [{ token: 'test-token' }, { token: 'other' }],
                                    productId: 7, runId: 'load-run', timeoutSeconds: 3 }),
        Date: class extends Date { static now() { return now; } },
        console: { error() {}, log: (line) => logs.push(line) },
    });
    const modules = { 'k6/http': { default: http },
        k6: { sleep: (seconds) => { now += seconds * 1000; } },
        'k6/metrics': { Counter: Metric, Trend: Metric } };
    const source = new vm.SourceTextModule(readFileSync(join(__dirname, 'scripts/concurrent-orders.js'), 'utf8'), { context });
    await source.link(name => new vm.SyntheticModule(Object.keys(modules[name]), function () {
        for (const [key, value] of Object.entries(modules[name])) this.setExport(key, value);
    }, { context }));
    await source.evaluate();
    source.namespace.default(source.namespace.setup());
    return { calls, metrics, logs, options: source.namespace.options,
             sum: (name) => (metrics[name] || []).reduce((a, b) => a + b, 0) };
}

const created = { status: 202, data: { id: 10, status: 'PENDING' } };
const confirmed = { status: 200, data: { status: 'CONFIRMED' } };

test('202 is followed to confirmation, with separate acceptance and completion samples', async () => {
    const r = await execute([created], [{ status: 200, data: { status: 'PENDING' } }, confirmed]);
    assert.equal(r.sum('order_successes'), 1);
    assert.equal(r.sum('order_failures'), 0);
    assert.equal(r.metrics.order_acceptance_ms[0], 100);
    assert.equal(r.metrics.saga_completion_time[0], 600);
    assert.equal(r.metrics.checkout_completion_ms[0], 700);
    assert.equal(r.sum('completion_samples'), 1);
    const data = JSON.parse(r.logs[0].slice('LOAD_ORDER '.length));
    assert.equal(data.confirmedAt - data.submittedAt, 700);
    assert.ok(!r.logs.join('').includes('test-token'));
    assert.equal(r.options.scenarios.concurrent_orders.executor, 'per-vu-iterations');
    assert.equal(r.options.thresholds.saga_completion_time[0], 'p(95)<3000');
    assert.equal(r.options.thresholds.completion_samples[0], 'count==2');
});

test('an ambiguous reply retries the same payload/key; acceptance includes retry time', async () => {
    const r = await execute([{ status: 0 }, created], [confirmed]);
    assert.equal(r.calls.length, 2);
    assert.deepEqual(r.calls[0], r.calls[1]);
    assert.equal(r.calls[0].key, 'load-run-1');
    assert.equal(r.metrics.order_acceptance_ms[0], 1200);
    assert.equal(JSON.parse(r.logs[0].slice('LOAD_ORDER '.length)).attempts, 2);
});

test('synchronous confirmation still produces a real completion sample', async () => {
    const r = await execute([{ status: 201, data: { id: 10, status: 'CONFIRMED' } }]);
    assert.equal(r.sum('order_successes'), 1);
    assert.equal(r.metrics.saga_completion_time[0], 0);
    assert.equal(r.metrics.checkout_completion_ms[0], 100);
});

for (const [name, response] of [
    ['rate limited', { status: 429 }], ['unauthorized', { status: 401 }],
    ['malformed acceptance', { status: 202, malformed: true }],
    ['missing order ID', { status: 202, data: {} }],
]) test(`${name} fails rather than producing a success latency`, async () => {
    const r = await execute([response]);
    assert.equal(r.sum('order_failures'), 1);
    assert.equal(r.sum('order_successes'), 0);
    assert.equal(r.sum('completion_samples'), 0);
    assert.equal(r.metrics.saga_completion_time.length, 0);
});

test('a cancelled order with sufficient stock is a failure', async () => {
    const r = await execute([created], [{ status: 200, data: { status: 'CANCELLED' } }]);
    assert.equal(r.sum('order_failures'), 1);
    assert.equal(r.sum('completion_samples'), 0);
});

test('polling timeout cannot disappear from the result as a fast completion', async () => {
    const r = await execute([created]);
    assert.equal(r.sum('order_failures'), 1);
    assert.equal(r.sum('completion_samples'), 0);
    assert.equal(r.metrics.saga_completion_time.length, 0);
});
