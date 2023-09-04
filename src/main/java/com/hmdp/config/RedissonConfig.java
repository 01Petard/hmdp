package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

//@Configuration
public class RedissonConfig {
    /**
     * 本地的redis
     */
//    @Bean
    public RedissonClient redissonClient() {
        // 配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://localhost:6379");
        // 创建RedissonClient对象
        return Redisson.create(config);
    }

    /**
     * 虚拟机上用Docker部署的三个redis
     */
//    @Bean
    public RedissonClient redissonClient1() {
        // 配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.113.129:6379");
        // 创建RedissonClient对象
        return Redisson.create(config);
    }

//    @Bean
    public RedissonClient redissonClient2() {
        // 配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.113.129:6380");
        // 创建RedissonClient对象
        return Redisson.create(config);
    }

//    @Bean
    public RedissonClient redissonClient3() {
        // 配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.113.129:6381");
        // 创建RedissonClient对象
        return Redisson.create(config);
    }
}
