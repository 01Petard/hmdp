-- 比较线程标示与锁中的标示是否一致
if (redis.call('get', KEYS[1]) == ARGV[1]) then
    -- 释放锁 del key
    return redis.call('del', KEYS[1])
end
return 0


----等效于以下代码：
---- 获取锁的key
--local key = KEYS[1]
---- 获取当前线程的标识
--local threadId = ARGV[1]
--
--
---- 获取锁中的线程标识  get key
--local id = redis.call('get', key)
--
---- 比较线程标示与锁中的标示是否一致
--if (id == threadId) then
--    -- 释放锁  del key
--    return redis.call('del', key)
--end
--return 0