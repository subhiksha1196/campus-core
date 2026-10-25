-- Token bucket rate limiter. Atomic: Redis runs the whole script without interleaving other commands,
-- so every app server behind the load balancer shares one bucket per client.
--
-- A bucket holds up to CAPACITY tokens and refills continuously at REFILL_PER_SEC tokens per second.
-- Each request takes COST tokens. A client can burst up to CAPACITY requests at once, but over time can
-- only sustain REFILL_PER_SEC requests per second. There is no window boundary, so there is no "double
-- burst across the window edge" (fixed window) and tokens trickle back straight after a spike.
--
-- KEYS[1]  bucket key (a hash with fields "tokens" and "ts")
-- ARGV[1]  capacity         max tokens = burst size                 (number > 0)
-- ARGV[2]  refill_per_sec   tokens added per second                 (number > 0)
-- ARGV[3]  cost             tokens this request takes, normally 1   (number > 0)
--
-- Returns { allowed, remaining, retry_after_ms }
--   allowed         1 if the request may proceed, 0 if rejected
--   remaining       whole tokens left after this request
--   retry_after_ms  0 when allowed, else milliseconds until COST tokens are available
--
-- Time comes from Redis (TIME), not from the app servers, so clock differences between servers cannot
-- change how fast a bucket refills. Needs Redis 5 or newer.

local key            = KEYS[1]
local capacity       = tonumber(ARGV[1])
local refill_per_sec = tonumber(ARGV[2])
local cost           = tonumber(ARGV[3])

if capacity == nil or refill_per_sec == nil or cost == nil
        or capacity <= 0 or refill_per_sec <= 0 or cost <= 0 then
    return redis.error_reply('token_bucket: capacity, refill_per_sec and cost must be numbers > 0')
end

local t   = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)   -- milliseconds

local state  = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts     = tonumber(state[2])

if tokens == nil or ts == nil then
    -- New client, or the key expired (it only expires once the bucket would be full again).
    tokens = capacity
    ts     = now
end

-- Add the tokens earned since the last request, never above capacity.
local elapsed = math.max(0, now - ts)
tokens = math.min(capacity, tokens + (elapsed * refill_per_sec) / 1000)

local allowed        = 0
local retry_after_ms = 0
if tokens >= cost then
    tokens  = tokens - cost
    allowed = 1
else
    retry_after_ms = math.ceil(((cost - tokens) * 1000) / refill_per_sec)
end

redis.call('HSET', key, 'tokens', tokens, 'ts', now)

-- Once the bucket would be full again it looks like a brand new one, so the key can expire.
-- This keeps Redis memory proportional to the clients active in the last few seconds.
redis.call('PEXPIRE', key, math.ceil((capacity / refill_per_sec) * 1000) + 1000)

return { allowed, math.floor(tokens), retry_after_ms }
