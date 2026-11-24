-- Token Bucket Rate Limiter Lua Script (ADR-001)
-- Keys: KEYS[1] = rl:rpm:{tenantId}
-- Args: ARGV[1] = max_capacity (rpmLimit), ARGV[2] = refill_rate_per_sec, ARGV[3] = current_time_ms, ARGV[4] = requested_tokens

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])

-- Get current bucket state
local bucket = redis.call('HMGET', key, 'tokens', 'last_refill_ms')
local current_tokens = tonumber(bucket[1])
local last_refill_ms = tonumber(bucket[2])

if not current_tokens or not last_refill_ms then
    -- Initial state: bucket is full
    current_tokens = capacity
    last_refill_ms = now
else
    -- Calculate refilled tokens based on elapsed time
    local elapsed_ms = math.max(0, now - last_refill_ms)
    local tokens_to_add = (elapsed_ms / 1000.0) * refill_rate
    current_tokens = math.min(capacity, current_tokens + tokens_to_add)
    last_refill_ms = now
end

if current_tokens >= requested then
    -- Consume tokens
    current_tokens = current_tokens - requested
    redis.call('HMSET', key, 'tokens', current_tokens, 'last_refill_ms', last_refill_ms)
    redis.call('EXPIRE', key, 120) -- 2 minutes TTL
    return {1, math.floor(current_tokens), 0} -- allowed, remaining_tokens, retry_after_seconds
else
    -- Rate limit exceeded
    local missing_tokens = requested - current_tokens
    local retry_after_sec = math.ceil(missing_tokens / refill_rate)
    redis.call('HMSET', key, 'tokens', current_tokens, 'last_refill_ms', last_refill_ms)
    redis.call('EXPIRE', key, 120)
    return {0, math.floor(current_tokens), math.max(1, retry_after_sec)} -- rejected, remaining_tokens, retry_after_seconds
end
