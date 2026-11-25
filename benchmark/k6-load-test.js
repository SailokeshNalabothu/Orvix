import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';

// ==============================================================================
// Orvix Distributed AI Control Plane - Comprehensive k6 Benchmark Suite
// ==============================================================================

// Custom SLA Metrics
export const gatewayLatency = new Trend('gateway_total_latency_ms');
export const cacheHitLatency = new Trend('cache_hit_latency_ms');
export const cacheMissLatency = new Trend('cache_miss_latency_ms');
export const failoverLatency = new Trend('failover_latency_ms');
export const cacheHitRate = new Rate('semantic_cache_hit_rate');
export const failoverRate = new Rate('provider_failover_rate');
export const successfulRequests = new Counter('successful_requests');

export const options = {
    scenarios: {
        // Scenario 1: Sustained High-Throughput Load (Constant Arrival Rate)
        sustained_throughput: {
            executor: 'constant-arrival-rate',
            rate: 60,                // 60 requests/second target
            timeUnit: '1s',
            duration: '15s',
            preAllocatedVUs: 40,
            maxVUs: 100,
        },
    },
    thresholds: {
        'http_req_duration': ['p(95)<400', 'p(99)<800'], // p95 < 400ms, p99 < 800ms
        'http_req_failed': ['rate<0.01'],                 // < 1% error rate
        'semantic_cache_hit_rate': ['rate>0.50'],         // > 50% cache hit on repeat queries
        'failover_latency_ms': ['p(95)<200'],             // Failover under 200ms SLA
    },
};

const BASE_URL = __ENV.GATEWAY_URL || 'http://localhost:8080';

// Multi-tenant API Keys for Concurrent Multi-Tenant Testing
const TENANT_KEYS = [
    'orv_live_benchmark_tenant_alpha',
    'orv_live_benchmark_tenant_beta',
    'orv_live_benchmark_tenant_gamma',
    'orv_live_benchmark_tenant_delta',
    'orv_live_benchmark_tenant_omega',
];

const samplePrompts = [
    "What is the CAP theorem in distributed systems?",
    "Explain CAP theorem in distributed architecture",       // Semantic match to #0
    "What is Raft consensus algorithm?",
    "Explain Raft consensus mechanism",                       // Semantic match to #2
    "How does optimistic locking prevent lost updates?",
    "What is HNSW vector indexing in pgvector?",
    "How to configure connection pooling in HikariCP?",
    "Explain double-entry bookkeeping in FinOps ledger",
];

export default function () {
    // 1. Concurrent Tenant Selection
    const tenantKey = TENANT_KEYS[__VU % TENANT_KEYS.length];

    // 2. Failure Injection: 5% of traffic injects simulated primary LLM failure
    const isChaosFailure = Math.random() < 0.05;
    let prompt;
    if (isChaosFailure) {
        prompt = "Explain distributed tracing [SIMULATE_PRIMARY_FAILURE]";
    } else {
        const promptIndex = Math.floor(Math.random() * samplePrompts.length);
        prompt = samplePrompts[promptIndex];
    }

    const payload = JSON.stringify({
        model: 'auto-cascade',
        messages: [{ role: 'user', content: prompt }],
        cache_enabled: true
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
            'X-API-Key': tenantKey,
            'X-Idempotency-Key': `k6_req_${__VU}_${__ITER}_${Date.now()}`
        },
        timeout: '5s'
    };

    const startTime = Date.now();
    const res = http.post(`${BASE_URL}/v1/chat/completions`, payload, params);
    const durationMs = Date.now() - startTime;

    gatewayLatency.add(res.timings.duration);

    const cacheStatus = res.headers['X-Cache-Status'];
    const providerUsed = res.headers['X-Provider-Used'];

    const isHit = cacheStatus === 'HIT';
    cacheHitRate.add(isHit);

    if (isHit) {
        cacheHitLatency.add(res.timings.duration);
    } else if (providerUsed === 'gemini-primary') {
        cacheMissLatency.add(res.timings.duration);
    }

    const isFallback = providerUsed === 'openai-fallback';
    failoverRate.add(isFallback);

    if (isFallback) {
        failoverLatency.add(res.timings.duration);
    }

    if (res.status === 200) {
        successfulRequests.add(1);
    }

    check(res, {
        'status is 200': (r) => r.status === 200,
        'has trace id': (r) => r.headers['X-Trace-Id'] !== undefined,
        'has provider used': (r) => r.headers['X-Provider-Used'] !== undefined,
        'failover served by fallback': (r) => !isChaosFailure || r.headers['X-Provider-Used'] === 'openai-fallback',
    });
}
