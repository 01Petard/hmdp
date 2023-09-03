package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    /**
     * 业务的名称，或，锁的名称
     */
    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    /**
     * 获取线程的标识，然后缓存该线程的标识，作为锁
     * @param timeoutSec 锁持有的超时时间，过期后自动释放
     * @return
     */
    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    /**
     * 非分布式锁的解锁：针对单机
     * 获取锁的标识，与当前操作线程标识进行比较，当一致时解锁
     */
//    @Override
//    public void unlock() {
//        // 获取线程标示
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        // 获取锁中的标示
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        // 判断“锁中的标示”与“线程标示”是否一致
//        if(threadId.equals(id)) {
//            // 释放锁
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }

    /**
     * 分布式锁的解锁：针对集群
     * 调用lua脚本比较线程标示与锁中的标示是否一致
     * 保证原子性
     */
    @Override
    public void unlock() {
//        stringRedisTemplate.delete(KEY_PREFIX + name);
        // 调用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),ID_PREFIX + Thread.currentThread().getId()
        );
    }
}
