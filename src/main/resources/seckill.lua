-- 参数列表
local voucherId = ARGV[1]
local userId = ARGV[2]

--数据key
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

--脚本业务
if( tonumber(redis.call('get',stockKey)) <=0 ) then
    return 1
end

if( redis.call('sismember',orderKey,userId) == 1 ) then
    return 2
end

redis.call('incrby',stockKey,-1)

redis.call('sadd',orderKey,userId)

return 0

