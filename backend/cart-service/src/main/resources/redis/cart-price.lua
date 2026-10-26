local field = 'p:' .. ARGV[1]
local raw = redis.call('HGET', KEYS[1], field)
if not raw then
    redis.call('ZREM', KEYS[2], KEYS[1])
    return 0
end
local item = cjson.decode(raw)
local timestamp = tonumber(ARGV[4])
if item.priceUpdatedAt and tonumber(item.priceUpdatedAt) >= timestamp then return 0 end
item.price = ARGV[2]
item.productName = ARGV[3]
item.priceUpdatedAt = timestamp
redis.call('HSET', KEYS[1], field, cjson.encode(item))
return 1
