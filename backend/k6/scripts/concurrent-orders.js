import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { getToken } from './helpers/auth.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
// PRODUCT_ID: Pre-seeded product with sufficient stock (>= 100)
const PRODUCT_ID = __ENV.PRODUCT_ID || '1';

// Custom metrics
const sagaCompletionTime = new Trend('saga_completion_time', true);
const oversellErrors = new Counter('oversell_errors');
const orderSuccesses = new Counter('order_successes');
const orderFailures = new Counter('order_failures');

export const options = {
    scenarios: {
        concurrent_orders: {
            // AC2: 100 concurrent order placements — all 100 VUs fire simultaneously
            executor: 'shared-iterations',
            vus: 100,
            iterations: 100,
            maxDuration: '120s',
        },
    },
    thresholds: {
        // NFR3: Saga completion < 3 seconds (p95) — measured from order-accepted (2xx) to CONFIRMED
        'saga_completion_time': ['p(95)<3000'],
        // NFR6: No oversell — 0 stock violations
        'oversell_errors': ['count==0'],
        // HTTP errors should be minimal
        'http_req_failed': ['rate<0.01'],
    },
    // Allow enough time for 100 sequential token acquisitions in setup
    setupTimeout: '120s',
};

// VU setup: each VU authenticates once before load test starts
export function setup() {
    const tokens = [];
    for (let i = 0; i < 100; i++) {
        // Pre-seeded test users: testuser001@robomart.com ... testuser100@robomart.com
        const token = getToken(
            `testuser${String(i + 1).padStart(3, '0')}@robomart.com`,
            'testpassword123'
        );
        tokens.push(token);
    }
    return { tokens };
}

export default function (data) {
    const token = data.tokens[__VU % data.tokens.length];

    if (!token) {
        console.error('No token available for VU', __VU);
        return;
    }

    const headers = {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`,
    };

    const orderPayload = JSON.stringify({
        items: [{ productId: parseInt(PRODUCT_ID), quantity: 1 }],
        shippingAddress: '1 Load Test St, Austin, TX, 75001, US',
    });

    const orderRes = http.post(`${BASE_URL}/api/v1/orders`, orderPayload, {
        headers,
        tags: { name: 'orders/create' },
    });

    if (orderRes.status === 201 || orderRes.status === 200) {
        const body = JSON.parse(orderRes.body);
        const orderId = body?.data?.id;
        if (!orderId) {
            orderFailures.add(1);
            return;
        }

        // Measure saga completion from order accepted (2xx response) to CONFIRMED.
        // Excludes order-placement roundtrip to measure only saga processing time (NFR3).
        const sagaStart = Date.now();
        let confirmed = false;
        for (let attempt = 0; attempt < 5; attempt++) {
            sleep(1);
            const statusRes = http.get(`${BASE_URL}/api/v1/orders/${orderId}`, { headers });
            const status = JSON.parse(statusRes.body)?.data?.status;

            if (status === 'CONFIRMED') {
                confirmed = true;
                break;
            }
            if (status === 'CANCELLED' || status === 'FAILED') {
                break;
            }
        }

        const sagaElapsed = Date.now() - sagaStart;
        sagaCompletionTime.add(sagaElapsed);

        if (confirmed) {
            orderSuccesses.add(1);
        } else {
            orderFailures.add(1);
        }
    } else if (orderRes.status === 409) {
        // OUT_OF_STOCK or INVENTORY_INSUFFICIENT — expected compensation result
        const body = JSON.parse(orderRes.body);
        if (body?.error?.code === 'OVERSELL_DETECTED') {
            oversellErrors.add(1);  // NFR6 violation — must be 0
        }
        orderFailures.add(1);
    } else {
        orderFailures.add(1);
        console.error(`Unexpected status: ${orderRes.status} - ${orderRes.body.substring(0, 100)}`);
    }
}
