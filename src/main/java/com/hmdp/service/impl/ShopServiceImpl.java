package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

//    @Resource
//    private CacheClient cacheClient;

    @Override
    public Result queryShopById(Long id) {
        //使用工具类的方法解决缓存穿透
        CacheClient cacheClient = new CacheClient(
                stringRedisTemplate,
                RedisConstants.CACHE_SHOP_KEY,  //缓存名
                RedisConstants.CACHE_SHOP_TTL,  //缓存时间
                TimeUnit.MINUTES,               //缓存分钟
                RedisConstants.LOCK_SHOP_TTL,   //互斥锁时长
                TimeUnit.SECONDS,               //互斥锁秒数
                RedisConstants.CACHE_SHOP_TTL,  //逻辑过期时间
                TimeUnit.MINUTES,               //过期分钟
                RedisConstants.CACHE_NULL_TTL,  //击穿返回空值时长
                TimeUnit.MINUTES);              //空值分钟
        //使用互斥锁查询
//        Shop shop = cacheClient.queryWithMutex(id, Shop.class, this::getById);
        //使用逻辑过期查询
        Shop shop = cacheClient.queryWithLogicExpire(id, Shop.class, this::getById);

//        //使用互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
//        //使用逻辑过期解决缓存击穿
//        Shop shop = queryWithLogicExpire(id);
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    /**
     * 查询店铺，解决缓存击穿
     * @param id
     * @return
     */
    public Shop queryById(Long id){
        //解决缓存击穿
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中的是否是空值（缓存穿透）
        if (shopJson != null) {
            return null;
        }
        //店铺不在redis中，去查数据库，并缓存到redis
        Shop shop = getById(id);
        //解决缓存穿透
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }



    /**
     * 使用“互斥锁”避免缓存击穿
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {
        //从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //如果缓存非空，就转换成返回对象
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //缓存穿透：如果缓存判断命中的是否是空值
        if (shopJson != null) {
            return null;
        }
        Shop shop = null;
        try {
            //缓存重建：获取互斥锁、判断获取是否成功、若失败则休眠，若成功就去查数据库，并写入缓存，释放互斥锁
            if (!tryLock(id)) { // 获取互斥锁失败，则休眠后再尝试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //店铺不在redis中，去查数据库，并缓存到redis
            shop = getById(id);
//            Thread.sleep(200); //模拟
            if (shop == null) {
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                //缓存穿透：对穿透结果返回空值
                return null;
            }
            //查完数据库后，缓存到redis
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放互斥锁
            unLock(id);
        }
        return shop;
    }

    /**
     * 尝试获取”互斥锁“
     *
     * @return
     */
    private Boolean tryLock(Long shopId) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(RedisConstants.CACHE_SHOP_KEY + shopId + ":mutex", "mutex", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 尝试获取”互斥锁“
     *
     * @param shopId
     */
    private void unLock(Long shopId) {
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shopId + ":mutex");
    }


    private static  final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 使用“逻辑过期”避免缓存击穿
     * @param id
     * @return
     */
    public Shop queryWithLogicExpire(Long id) {
        //从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //如果缓存未命中，返回空
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }
        //如果缓存命中，判断缓存是否过期
        //获取商铺信息反序列化json，获取时间并判断是否过期
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())){
            //未过期，直接返回店铺
            return shop;
        }else {
            //过期了，重建缓存
            //获取互斥锁
            if (tryLock(id)){
                CACHE_REBUILD_EXECUTOR.submit(() ->{
                    try {
                        //重建缓存
                        this.saveShop2RedisWithLogicExpire(id, RedisConstants.CACHE_SHOP_TTL);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        unLock(id);
                    }
                });
            }
        }
        return shop;
    }

    /**
     * 逻辑过期：往redis添加一个需要手动控制过期时间的key
     * @param id
     * @param expireSeconds
     */
    public void saveShop2RedisWithLogicExpire(Long id, Long expireSeconds) {
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }


    /**
     * 更新店铺
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Object updateShopById(Shop shop) {
        if (shop.getId() == null) {
            return Result.fail("店铺id不能为空！");
        }
        //1、更新数据库
        updateById(shop);
        //2、删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

}
