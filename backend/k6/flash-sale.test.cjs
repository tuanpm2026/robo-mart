// Run the actual k6 module against in-memory HTTP/clock adapters; no live services needed.
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { test } = require('node:test');
const vm = require('node:vm');

async function execute(posts, gets = []) {
    const calls = [];
    const metrics = {};
    let now = 0;
    class Metric {
        constructor(name) { this.name = name; metrics[name] = 0; }
        add(value) { metrics[this.name] += value; }
    }
    const response = (value) => ({
        status: value.status,
        json() {
            if (value.malformed) throw new Error('Malformed response');
            return { data: value.data };
        },
    });
    const http = {
        post(url, body, options) {
            calls.push({ url, body, key: options.headers['Idempotency-Key'] });
            assert.ok(posts.length, 'Unexpected checkout request');
            return response(posts.shift());
        },
        get() {
            return response(gets.shift() || { status: 503 });
        },
    };
    const context = vm.createContext({
        __VU: 1,
        __ENV: { FLASH_SALE_FIXTURE: 'private-fixture', FLASH_SALE_SUMMARY: 'summary.json' },
        open: () => JSON.stringify({ customers: [{ token: 'test-token' }, { token: 'other' }],
                                    productId: 7, runId: 'test-run', timeoutSeconds: 3 }),
        Date: class extends Date { static now() { return now; } },
        console: { error() {} },
    });
    const modules = {
        'k6/http': { default: http },
        k6: { sleep: (seconds) => { now += seconds * 1000; } },
        'k6/metrics': { Counter: Metric, Trend: Metric },
    };
    const source = new vm.SourceTextModule(readFileSync(join(__dirname, 'scripts/flash-sale.js'), 'utf8'),
                                          { context });
    await source.link((name) => new vm.SyntheticModule(Object.keys(modules[name]), function () {
        for (const [key, value] of Object.entries(modules[name])) this.setExport(key, value);
    }, { context }));
    await source.evaluate();
    source.namespace.default(source.namespace.setup());
    return { metrics, calls, options: source.namespace.options };
}

test('202/PENDING is polled to confirmation and replay keeps the original key/payload', async () => {
    const result = await execute([{ status: 202, data: { id: 10, status: 'PENDING' } },
                                 { status: 200, data: { id: 10 } }],
                                [{ status: 200, data: { status: 'PENDING' } },
                                 { status: 200, data: { status: 'CONFIRMED' } }]);
    assert.equal(result.metrics.successful_orders, 1);
    assert.equal(result.metrics.unexpected_outcomes, 0);
    assert.equal(result.metrics.replay_mismatches, 0);
    assert.deepEqual(result.calls[0], result.calls[1]);
    assert.equal(result.calls[0].key, 'test-run-1');
    assert.equal(result.options.scenarios.flash_sale.executor, 'per-vu-iterations');
});

test('a lost HTTP reply retries the exact original checkout', async () => {
    const result = await execute([{ status: 0 }, { status: 202, data: { id: 10 } },
                                 { status: 200, data: { id: 10 } }],
                                [{ status: 200, data: { status: 'CONFIRMED' } }]);
    assert.equal(result.metrics.successful_orders, 1);
    assert.equal(result.calls.length, 3);
    assert.ok(result.calls.every(call => call.body === result.calls[0].body && call.key === 'test-run-1'));
});

test('a replay returning another order ID fails its threshold', async () => {
    const result = await execute([{ status: 202, data: { id: 10 } },
                                 { status: 200, data: { id: 11 } }],
                                [{ status: 200, data: { status: 'CONFIRMED' } }]);
    assert.equal(result.metrics.replay_mismatches, 1);
});

test('an unreadable accepted response cannot count as a stock rejection', async () => {
    const result = await execute([{ status: 202, malformed: true }]);
    assert.equal(result.metrics.unexpected_outcomes, 1);
    assert.equal(result.metrics.cancelled_orders, 0);
});

test('rate limiting is an unexpected outcome, not an out-of-stock result', async () => {
    const result = await execute([{ status: 429 }]);
    assert.equal(result.metrics.unexpected_outcomes, 1);
    assert.equal(result.metrics.cancelled_orders, 0);
});

test('an unresolved order fails after the bounded polling deadline', async () => {
    const result = await execute([{ status: 202, data: { id: 10 } },
                                 { status: 200, data: { id: 10 } }]);
    assert.equal(result.metrics.unexpected_outcomes, 1);
    assert.equal(result.metrics.successful_orders, 0);
});
