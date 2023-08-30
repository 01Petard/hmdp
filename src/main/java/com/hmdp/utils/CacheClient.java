package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Configuration
public class CacheClient {

    //缓存的前缀
    private String cacheKeyPrefix;
    //缓存的TTL
    private Long cacheTTL;
    //缓存TTL的时间单位
    private TimeUnit cacheTTLTimeUnit;

    //缓存的互斥锁过期TTL
    private Long cacheMutexTTL;
    //缓存互斥锁过期TTL的时间单位
    private TimeUnit cacheMutexTTLTimeUnit;

    //缓存的逻辑过期TTL
    private Long cacheLogicExpireTTL;
    //缓存逻辑过期TTL的时间单位
    private TimeUnit cacheLogicExpireTTLTimeUnit;

    //缓存击穿后的设置为空返回值的TTL
    private Long cacheMissingTTL;
    //缓存击穿返回空值的TTL的时间单位
    private TimeUnit cacheMissingTTLTimeUnit;


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public CacheClient() {
    }

    public CacheClient(
            StringRedisTemplate stringRedisTemplate,
            String cacheKeyPrefix,
            Long cacheTTL,
            TimeUnit cacheTTLTimeUnit,
            Long cacheMutexTTL,
            TimeUnit cacheMutexTTLTimeUnit,
            Long cacheLogicExpireTTL,
            TimeUnit cacheLogicExpireTTLTimeUnit,
            Long cacheMissingTTL,
            TimeUnit cacheMissingTTLTimeUnit
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.cacheKeyPrefix = cacheKeyPrefix;
        this.cacheTTL = cacheTTL;
        this.cacheTTLTimeUnit = cacheTTLTimeUnit;
        this.cacheMutexTTL = cacheMutexTTL;
        this.cacheMutexTTLTimeUnit = cacheMutexTTLTimeUnit;
        this.cacheLogicExpireTTL = cacheLogicExpireTTL;
        this.cacheLogicExpireTTLTimeUnit = cacheLogicExpireTTLTimeUnit;
        this.cacheMissingTTL = cacheMissingTTL;
        this.cacheMissingTTLTimeUnit = cacheMissingTTLTimeUnit;
    }


    /**
     * 添加缓存，TTL过期
     *
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithTTL(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 添加缓存，逻辑过期
     *
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogicExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData), time, unit);
    }


    /**
     * （逻辑过期）往redis添加一个需要手动控制过期时间的key
     * 在使用逻辑过期办法查询时，如果第一次查询缓存中没有，就会使用一次互斥锁查询
     *
     * @param key
     * @param id
     * @param dbFallback
     * @param expireMinutes
     * @param <T>
     * @param <ID>
     * @return
     */
    public <T, ID> T setWithLogicExpire(String key, ID id, Class<T> type, Function<ID, T> dbFallback, Long expireMinutes) {
        T t = this.queryWithMutex(id, type, dbFallback);
//        T t = dbFallback.apply(id);
        RedisData redisData = new RedisData();
        redisData.setData(t);
        redisData.setExpireTime(LocalDateTime.now().plusMinutes(expireMinutes));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
        return t;
    }

    /**
     * 在互斥锁查询部分，使用该方法设置逻辑过期
     *
     * @param key
     * @param t
     * @param expireMinutes
     * @param <T>
     */
    public <T> void setWithLogicExpire(String key, T t, Long expireMinutes) {
        RedisData redisData = new RedisData();
        redisData.setData(t);
        redisData.setExpireTime(LocalDateTime.now().plusMinutes(expireMinutes));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    /**
     * 获取值，避免缓存穿透
     *
     * @param id
     * @param type
     * @param dbFallback
     * @param <T>
     * @param <ID>
     * @return
     */
    public <T, ID> T queryWithMutex(ID id, Class<T> type, Function<ID, T> dbFallback) {
        String key = this.cacheKeyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //如果缓存命中且非空（非null、“”、“ ”），就转换成返回对象
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        //缓存穿透：如果缓存命中，判读是否是空值
        if (json != null) {
            return null;
        }
        //去数据库查询，查询出来后缓存进redis
        T t = null;
        try {
            //获取互斥锁，若失败则休眠后再尝试
            if (!tryLock(key + ":mutex", this.cacheMutexTTL, this.cacheMutexTTLTimeUnit)) {
                Thread.sleep(50);
                return queryWithMutex(id, type, dbFallback);
            }
            //获取互斥锁成功，则根据id查询对象，若查不到则缓存空值，并返回空值
            t = dbFallback.apply(id);
            //解决缓存穿透
            if (t == null) {
                stringRedisTemplate.opsForValue().set(key, "", this.cacheMissingTTL, this.cacheMissingTTLTimeUnit);
                return null;
            }
            //若数据库里有，就缓存到redis，并返回数据
//            this.setWithTTL(key, t, this.cacheTTL, this.cacheTTLTimeUnit);
            this.setWithLogicExpire(key, t, this.cacheTTL);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unLock(key + ":mutex");
        }
        return t;


    }

    /**
     * 尝试获取”互斥锁“
     *
     * @param key
     * @param time
     * @param unit
     * @return
     */
    private Boolean tryLock(String key, Long time, TimeUnit unit) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "mutex", time, unit);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 尝试获取”互斥锁“
     *
     * @param key
     */
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }


    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 使用“逻辑过期”避免缓存击穿
     *
     * @param id
     * @param type
     * @param dbFallback
     * @param <T>
     * @param <ID>
     * @return
     */
    public <T, ID> T queryWithLogicExpire(ID id, Class<T> type, Function<ID, T> dbFallback) {
        String key = this.cacheKeyPrefix + id;
        //从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //如果缓存未命中
        if (StrUtil.isBlank(json)) {
//            return null;  //（此情况下缓存重建由管理员负责维护，这一步返回空值，用户无法访问没访问过的数据）
            return setWithLogicExpire(key, id, type, dbFallback, this.cacheLogicExpireTTL);
        }
        //如果缓存命中，判断缓存是否过期
        //获取商铺信息反序列化json，获取时间并判断是否过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期，直接返回店铺
            return JSONUtil.toBean((JSONObject) redisData.getData(), type);
        } else { //缓存逻辑过期了，重建缓存
            T t = dbFallback.apply(id);
            //获取互斥锁
            if (tryLock(key + ":mutex", this.cacheMutexTTL, this.cacheMutexTTLTimeUnit)) {
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    try {
                        //重建缓存
                        this.setWithLogicExpire(key, t, this.cacheLogicExpireTTL);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        unLock(key + ":mutex");
                    }
                });
            }
            return t;
        }


//        if (tryLock(key + ":mutex", this.cacheMutexTTL, this.cacheMutexTTLTimeUnit)){
//            // 6.3.成功，开启独立线程，实现缓存重建
//            CACHE_REBUILD_EXECUTOR.submit(() -> {
//                try {
//                    // 查询数据库
//                    T t = dbFallback.apply(id);
//                    // 重建缓存
//                    this.setWithLogicExpire(key, id, type, dbFallback, this.cacheLogicExpireTTL);
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                }finally {
//                    // 释放锁
//                    unlock(lockKey);
//                }
//            });
//        }
//        return null;
    }


}
