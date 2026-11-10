import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const fixture = JSON.parse(open(__ENV.CONCURRENT_ORDERS_FIXTURE));
const API = 'http://127.0.0.1:18080';
const accepted = new Counter('accepted_orders');
const successes = new Counter('order_successes');
const failures = new Counter('order_failures');
const samples = new Counter('completion_samples');
const acceptance = new Trend('order_acceptance_ms', true);
// Preserve the existing <3s Saga completion objective, measured after API acceptance.
const completion = new Trend('saga_completion_time', true);
const endToEnd = new Trend('checkout_completion_ms', true);

export const options = {
    scenarios: {
        concurrent_orders: {
            executor: 'per-vu-iterations',
            vus: fixture.customers.length,
            iterations: 1,
            maxDuration: `${fixture.timeoutSeconds + 60}s`,
        },
    },
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
    thresholds: {
        accepted_orders: [`count==${fixture.customers.length}`],
        order_successes: [`count==${fixture.customers.length}`],
        completion_samples: [`count==${fixture.customers.length}`],
        order_failures: ['count==0'],
        iterations: [`count==${fixture.customers.length}`],
        saga_completion_time: ['p(95)<3000'],
        http_req_failed: ['rate<0.01'],
    },
};

export function setup() {
    return { startAt: Date.now() + 2000 };
}

function dataOf(response) {
    try { return response.json().data; } catch (_) { return null; }
}

export default function (data) {
    accepted.add(0);
    successes.add(0);
    failures.add(0);
    samples.add(0);
    const customer = fixture.customers[__VU - 1];
    const headers = {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${customer.token}`,
        'Idempotency-Key': `${fixture.runId}-${__VU}`,
    };
    const body = JSON.stringify({
        items: [{ productId: String(fixture.productId), quantity: 1 }],
        shippingAddress: '1 Load Test Fixture St, Austin, TX 75001, US',
    });
    sleep(Math.max(0, data.startAt - Date.now()) / 1000);
    const submittedAt = Date.now();
    let response;
    let attempts = 0;
    do {
        attempts++;
        response = http.post(`${API}/api/v1/orders`, body, {
            headers, timeout: '10s', tags: { name: 'load/create' },
        });
        if (response.status !== 0 && response.status < 500) break;
        if (attempts < 3) sleep(1);
    } while (attempts < 3);
    const acceptedAt = Date.now();
    const order = dataOf(response);
    let traceId = null;
    try { traceId = response.json().traceId || null; } catch (_) { /* No accepted body. */ }
    if (![200, 201, 202].includes(response.status) || !order?.id) {
        failures.add(1);
        console.error(`Customer ${__VU}: checkout HTTP ${response.status}, no accepted order`);
        return;
    }
    accepted.add(1);
    acceptance.add(acceptedAt - submittedAt);
    const deadline = submittedAt + fixture.timeoutSeconds * 1000;
    let current = order;
    while (Date.now() < deadline) {
        if (current?.status === 'CONFIRMED') {
            const confirmedAt = Date.now();
            successes.add(1);
            samples.add(1);
            completion.add(confirmedAt - acceptedAt);
            endToEnd.add(confirmedAt - submittedAt);
            // One machine-readable observation per customer; never log tokens or HTTP bodies.
            console.log('LOAD_ORDER ' + JSON.stringify({
                id: order.id, vu: __VU, submittedAt, acceptedAt, confirmedAt, attempts, traceId,
            }));
            return;
        }
        if (['CANCELLED', 'FAILED', 'REFUND_FAILED', 'SHIPPED', 'DELIVERED'].includes(current?.status)) break;
        const result = http.get(`${API}/api/v1/orders/${order.id}`, {
            headers, timeout: '10s', tags: { name: 'load/status' },
        });
        if ([401, 403, 404].includes(result.status)) break;
        current = result.status === 200 ? dataOf(result) : null;
        if (current?.status !== 'CONFIRMED') sleep(0.5);
    }
    failures.add(1);
    console.error(`Order ${order.id}: not confirmed within ${fixture.timeoutSeconds}s`);
}

export function handleSummary(data) {
    return {
        [__ENV.CONCURRENT_ORDERS_SUMMARY]: JSON.stringify(data, null, 2),
        stdout: `Concurrent orders: ${data.metrics.order_successes?.values.count || 0} confirmed; ` +
            `${data.metrics.order_failures?.values.count || 0} failures.\n`,
    };
}
