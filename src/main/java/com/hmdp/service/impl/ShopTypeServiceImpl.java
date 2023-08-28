package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryShopType() {
        String shopTypeJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + "shop-type");
        if (StrUtil.isNotBlank(shopTypeJson)){
            JSONArray shopTypeList = JSONUtil.parseArray(shopTypeJson);
            return Result.ok(shopTypeList);
        }
        //商铺类型不存在就去查数据库，查完并缓存
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        if (shopTypeList == null){
            return Result.fail("缺少店铺类型！");
        }
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + "shop-type", JSONUtil.toJsonStr(shopTypeList));
        return Result.ok(shopTypeList);
    }
}
