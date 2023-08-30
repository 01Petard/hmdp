package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Test
    void testMutex() {
        shopService.saveShop2RedisWithLogicExpire(1L, 10L);
    }

    @Test
    void testLogicExpire() {
        shopService.saveShop2RedisWithLogicExpire(1L, 10L);
    }

}
