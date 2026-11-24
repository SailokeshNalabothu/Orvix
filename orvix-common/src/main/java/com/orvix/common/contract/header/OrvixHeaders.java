package com.orvix.common.contract.header;

public final class OrvixHeaders {
    private OrvixHeaders() {}

    public static final String API_KEY = "X-API-Key";
    public static final String IDEMPOTENCY_KEY = "X-Idempotency-Key";
    public static final String TRACE_ID = "X-Trace-Id";
    public static final String CACHE_STATUS = "X-Cache-Status";
    public static final String PROVIDER_USED = "X-Provider-Used";
}
