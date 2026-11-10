-- All mutations and guest-to-user transfer are serialized by Redis, without a read/save window.
local cart = KEYS[1]
local expiry = KEYS[2]
local op = ARGV[1]
local ttl = tonumber(ARGV[2])
local now = tonumber(redis.call('TIME')[1])
local generation = ARGV[#ARGV]
local function index(k, untilTime)
    for _, field in ipairs(redis.call('HKEYS', k)) do
        if string.sub(field, 1, 2) == 'p:' then
            local reverse = 'cart:product:' .. string.sub(field, 3)
            local stale = redis.call('ZRANGEBYSCORE', reverse, '-inf', now, 'LIMIT', 0, 100)
            if #stale > 0 then redis.call('ZREM', reverse, unpack(stale)) end
            redis.call('ZADD', reverse, untilTime, k)
            redis.call('EXPIRE', reverse, ttl + 3600)
        end
    end
end
local function refresh(k)
    redis.call('EXPIRE', k, ttl)
    redis.call('ZADD', expiry, now + ttl, k)
    index(k, now + ttl)
end
-- One-time compatibility conversion of Spring Data Redis hashes, retaining their existing keys.
local function upgrade(k)
    if not redis.call('HGET', k, '_class') then return end
    local oldTtl = redis.call('PTTL', k)
    local fields = redis.call('HGETALL', k)
    local items = {}
    local metadata = {}
    for i = 1, #fields, 2 do
        local n, property = string.match(fields[i], '^items%.%[(%d+)%]%.(.+)$')
        if n then
            items[n] = items[n] or {}
            items[n][property] = fields[i+1]
        else metadata[fields[i]] = fields[i+1] end
    end
    for _, item in pairs(items) do
        if not item.productId or not item.quantity or not item.price then
            error('Malformed legacy cart; preserve for operator review')
        end
    end
    for i = 1, #fields, 2 do redis.call('HDEL', k, fields[i]) end
    redis.call('HSET', k, '__created', metadata.createdAt or ARGV[3], '__updated', metadata.updatedAt or ARGV[3],
        '__user', metadata.userId or '')
    for _, item in pairs(items) do
        local converted = {productId=item.productId, productName=item.productName,
            price=item.price, quantity=tonumber(item.quantity)}
        local latest = redis.call('GET', 'cart:catalog:' .. item.productId)
        if latest then
            local snapshot = cjson.decode(latest)
            converted.price = snapshot.price
            converted.productName = snapshot.productName
            converted.priceUpdatedAt = snapshot.priceUpdatedAt
        end
        redis.call('HSET', k, 'p:' .. item.productId, cjson.encode(converted))
    end
    if oldTtl > 0 then redis.call('PEXPIRE', k, oldTtl) end
    index(k, now + math.max(1, math.floor(oldTtl / 1000)))
    redis.call('ZADD', expiry, now + math.max(1, math.floor(oldTtl / 1000)), k)
    -- Retire the leaking Spring Data repository index for this converted cart.
    redis.call('SREM', 'cart', string.sub(k, 6))
end
local function identifyLines(k)
    for _, field in ipairs(redis.call('HKEYS', k)) do
        if string.sub(field, 1, 2) == 'p:' then
            local item = cjson.decode(redis.call('HGET', k, field))
            if not item.lineId then
                item.lineId = generation .. ':' .. string.sub(field, 3)
                redis.call('HSET', k, field, cjson.encode(item))
            end
        end
    end
end
upgrade(cart)
identifyLines(cart)
if op == 'MERGE' then
    local guest = KEYS[3]
    upgrade(guest)
    identifyLines(guest)
    redis.call('HSETNX', cart, '__created', ARGV[3])
    redis.call('HSET', cart, '__updated', ARGV[3], '__user', ARGV[4])
    local fields = redis.call('HGETALL', guest)
    for i = 1, #fields, 2 do
        local field = fields[i]
        if string.sub(field, 1, 2) == 'p:' then
            local incoming = cjson.decode(fields[i+1])
            local current = redis.call('HGET', cart, field)
            if current then
                local existing = cjson.decode(current)
                -- The authenticated cart's price snapshot is preferred; checkout reprices authoritatively.
                existing.quantity = math.min(999, existing.quantity + incoming.quantity)
                incoming = existing
            end
            redis.call('HSET', cart, field, cjson.encode(incoming))
            redis.call('ZREM', 'cart:product:' .. string.sub(field, 3), guest)
        end
    end
    redis.call('DEL', guest)
    redis.call('ZREM', expiry, guest)
    redis.call('DEL', 'cart:warned:' .. guest)
elseif op == 'ADD' then
    local field = 'p:' .. ARGV[4]
    local incoming = cjson.decode(ARGV[5])
    local current = redis.call('HGET', cart, field)
    incoming.lineId = generation .. ':' .. ARGV[4]
    if current then
        local existing = cjson.decode(current)
        incoming.quantity = math.min(999, incoming.quantity + existing.quantity)
        incoming.lineId = existing.lineId
        -- Preserve the event fence when a catalog lookup that began earlier finishes late.
        if existing.priceUpdatedAt then
            incoming.price = existing.price
            incoming.productName = existing.productName
            incoming.priceUpdatedAt = existing.priceUpdatedAt
        end
    end
    redis.call('HSET', cart, field, cjson.encode(incoming))
    redis.call('HSETNX', cart, '__created', ARGV[3])
    redis.call('HSET', cart, '__updated', ARGV[3])
    if ARGV[6] ~= '' then redis.call('HSET', cart, '__user', ARGV[6]) end
elseif op == 'SET' or op == 'REMOVE' then
    if redis.call('EXISTS', cart) == 0 then return {'MISSING_CART'} end
    local field = 'p:' .. ARGV[4]
    local current = redis.call('HGET', cart, field)
    if not current then return {'MISSING_ITEM'} end
    if op == 'SET' then
        local item = cjson.decode(current)
        item.quantity = tonumber(ARGV[5])
        item.lineId = generation .. ':' .. ARGV[4]
        redis.call('HSET', cart, field, cjson.encode(item))
    else
        redis.call('HDEL', cart, field)
        redis.call('ZREM', 'cart:product:' .. ARGV[4], cart)
    end
    redis.call('HSET', cart, '__updated', ARGV[3])
elseif op == 'CHECKOUT' then
    if redis.call('EXISTS', cart) == 0 then return {'MISSING_CART'} end
    local receipt = '__checkout:' .. ARGV[4]
    if redis.call('HEXISTS', cart, receipt) == 0 then
        for _, purchased in ipairs(cjson.decode(ARGV[5])) do
            local field = 'p:' .. tostring(purchased.productId)
            local value = redis.call('HGET', cart, field)
            if value then
                local item = cjson.decode(value)
                if item.lineId == purchased.lineId then
                    item.quantity = math.max(0, item.quantity - purchased.quantity)
                    if item.quantity == 0 then
                        redis.call('HDEL', cart, field)
                        redis.call('ZREM', 'cart:product:' .. tostring(purchased.productId), cart)
                    else
                        redis.call('HSET', cart, field, cjson.encode(item))
                    end
                end
            end
        end
        -- Retain the receipt for this cart's lifetime, including an empty cart.
        redis.call('HSET', cart, receipt, '1', '__updated', ARGV[3])
    end
end
if redis.call('EXISTS', cart) == 0 then return {'MISSING_CART'} end
if op ~= 'PEEK' then refresh(cart) end
return redis.call('HGETALL', cart)
