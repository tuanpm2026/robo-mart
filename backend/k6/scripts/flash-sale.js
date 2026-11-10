import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

// The Python runner owns disposable customers, stock, ledger probes and cleanup.
// Tokens live in a temporary mode-0600 file, never in logs or command arguments.
const fixture = JSON.parse(open(__ENV.FLASH_SALE_FIXTURE));
const BASE_URL = 'http://127.0.0.1:18080';
const successfulOrders = new Counter('successful_orders');
const cancelledOrders = new Counter('cancelled_orders');
const unexpectedOutcomes = new Counter('unexpected_outcomes');
const replayMismatches = new Counter('replay_mismatches');
const checkoutTime = new Trend('checkout_completion_ms', true);

export const options = {
    scenarios: {
        flash_sale: {
            // shared-iterations can reuse a faster VU/customer.
            executor: 'per-vu-iterations',
            vus: fixture.customers.length,
            iterations: 1,
            maxDuration: `${fixture.timeoutSeconds + 60}s`,
        },
    },
    thresholds: {
        successful_orders: ['count==1'],
        cancelled_orders: [`count==${fixture.customers.length - 1}`],
        unexpected_outcomes: ['count==0'],
        replay_mismatches: ['count==0'],
        iterations: [`count==${fixture.customers.length}`],
    },
};

export function setup() {
    // Align the burst without promising identical network arrival times.
    return { startAt: Date.now() + 2000 };
}

function dataOf(response) {
    try {
        return response.json().data;
    } catch (_) {
        return null;
    }
}

function place(body, headers) {
    // Ambiguous responses reuse BOTH the original payload and key.
    let response;
    for (let attempt = 0; attempt < 3; attempt++) {
        response = http.post(`${BASE_URL}/api/v1/orders`, body, {
            headers, timeout: '10s', tags: { name: 'flash-sale/create-or-replay' },
        });
        if (response.status !== 0 && response.status < 500) return response;
        sleep(1);
    }
    return response;
}

export default function (data) {
    successfulOrders.add(0);
    cancelledOrders.add(0);
    unexpectedOutcomes.add(0);
    replayMismatches.add(0);
    const customer = fixture.customers[__VU - 1];
    const headers = {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${customer.token}`,
        'Idempotency-Key': `${fixture.runId}-${__VU}`,
    };
    const body = JSON.stringify({
        items: [{ productId: String(fixture.productId), quantity: 1 }],
        shippingAddress: '1 Flash Sale Fixture St, Austin, TX 75001, US',
    });
    sleep(Math.max(0, data.startAt - Date.now()) / 1000);
    const start = Date.now();
    const created = place(body, headers);
    const order = dataOf(created);
    if (![200, 201, 202].includes(created.status) || !order?.id) {
        unexpectedOutcomes.add(1);
        console.error(`Customer ${__VU}: checkout HTTP ${created.status}, no accepted order`);
        return;
    }

    // Deliberately replay every accepted checkout, including 202/PENDING.
    const replay = place(body, headers);
    if (![200, 201, 202].includes(replay.status) || dataOf(replay)?.id !== order.id) {
        replayMismatches.add(1);
    }

    const deadline = start + fixture.timeoutSeconds * 1000;
    while (Date.now() < deadline) {
        const response = http.get(`${BASE_URL}/api/v1/orders/${order.id}`, {
            headers, timeout: '10s', tags: { name: 'flash-sale/status' },
        });
        const current = dataOf(response);
        if (response.status === 200 && current?.status === 'CONFIRMED') {
            successfulOrders.add(1);
            checkoutTime.add(Date.now() - start);
            return;
        }
        if (response.status === 200 && current?.status === 'CANCELLED') {
            // The runner checks the durable Saga reason; Order can expose a generic reason.
            cancelledOrders.add(1);
            return;
        }
        if ([401, 403, 404].includes(response.status)) break;
        sleep(2);
    }
    unexpectedOutcomes.add(1);
    console.error(`Order ${order.id}: unresolved checkout within ${fixture.timeoutSeconds}s`);
}

export function handleSummary(data) {
    return {
        [__ENV.FLASH_SALE_SUMMARY]: JSON.stringify(data, null, 2),
        stdout: `Flash sale: ${data.metrics.successful_orders?.values.count || 0} confirmed, ` +
            `${data.metrics.cancelled_orders?.values.count || 0} cancelled, ` +
            `${data.metrics.unexpected_outcomes?.values.count || 0} unexpected outcomes.\n`,
    };
}
