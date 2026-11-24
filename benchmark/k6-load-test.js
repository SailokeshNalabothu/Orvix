import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

// Custom SLA Metrics
export const gatewayLatency = new Trend('gateway_guardrail_latency_ms');
export const cacheHitRate = new Rate('semantic_cache_hit_rate');
export const failoverRate = new Rate('provider_failover_rate');

export const options = {
    scenarios: {
        // Sustained load test: aiming for high concurrency with Virtual Threads
        sustained_throughput: {
            executor: 'constant-arrival-rate',
            rate: 1000,
            timeUnit: '1s',
            duration: '30s',
            preAllocatedVUs: 100,
            maxVUs: 500,
        },
    },
    thresholds: {
        'http_req_duration': ['p(95)<100'], // p95 < 100ms for semantic cache hits / gateway
        'http_req_failed': ['rate<0.01'],    // < 1% error rate
    },
};

const BASE_URL = __ENV.GATEWAY_URL || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY || 'orv_live_benchmark_key_1234567890';

const samplePrompts = [
    "What is the CAP theorem?",
    "Explain CAP theorem in distributed systems", // Semantic match to #1
    "What is idempotency in Kafka?",
    "Explain idempotent event consumers",        // Semantic match to #3
    "How does optimistic locking prevent lost updates?",
    "What is HNSW vector indexing in pgvector?"
];

export default function () {
    // 40% repeated/paraphrased prompts to verify semantic cache hit SLA
    const promptIndex = Math.floor(Math.random() * samplePrompts.length);
    const prompt = samplePrompts[promptIndex];

    const payload = JSON.stringify({
        model: 'gpt-4o-mini',
        messages: [{ role: 'user', content: prompt }],
        temperature: 0.2,
        max_tokens: 256,
        cache_enabled: true
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
            'X-API-Key': API_KEY,
            'X-Idempotency-Key': `k6_req_${__VU}_${__ITER}`
        },
        timeout: '2s'
    };

    const res = http.post(`${BASE_URL}/v1/chat/completions`, payload, params);

    gatewayLatency.add(res.timings.duration);

    const isHit = res.headers['X-Cache-Status'] === 'HIT';
    cacheHitRate.add(isHit);

    const isFallback = res.headers['X-Provider-Used'] === 'openai-fallback';
    failoverRate.add(isFallback);

    check(res, {
        'status is 200': (r) => r.status === 200,
        'has trace id': (r) => r.headers['X-Trace-Id'] !== undefined,
        'has provider used': (r) => r.headers['X-Provider-Used'] !== undefined,
    });
}
