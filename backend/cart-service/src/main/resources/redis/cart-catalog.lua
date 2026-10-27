-- A bounded-lived latest snapshot closes the price-event window during legacy hash conversion.
local raw = redis.call('GET', KEYS[1])
local incoming = cjson.decode(ARGV[1])
if raw and tonumber(cjson.decode(raw).priceUpdatedAt) >= tonumber(incoming.priceUpdatedAt) then return 0 end
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
return 1
