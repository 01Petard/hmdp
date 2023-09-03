package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 秒杀券下单
     *
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        //1、查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2、判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            //秒杀尚未开始
            return Result.fail("秒杀尚未开始！");
        }
        //3、判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            //秒杀已经结束
            return Result.fail("秒杀已经结束！");
        }
        //4、判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("已经抢光啦！");
        }

        Long userId = UserHolder.getUser().getId();
        //创建分布式锁对象
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        //获取锁，并判断获取锁是否成功
        boolean isLock = lock.tryLock(1200);
        if (!isLock) {
            //获取锁失败
            return Result.fail("一人只能下一单哦！");
        }
        try {
            //获取事务有关的代理对象，这样只有当事务提交后才会放开锁，避免事务没提交就释放锁的情况
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } catch (Exception e) {
            log.error("ERROR: " + e.getMessage());
            return Result.fail("发生错误：" + e.getMessage());
        } finally {
            lock.unlock();
        }
//        //单体锁（非分布式锁）
//        synchronized (userId.toString().intern()) {
//            //获取事务有关的代理对象，这样只有当事务提交后才会放开锁，避免事务没提交就释放锁的情况
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }
    }

    /**
     * 创建订单
     *
     * @param voucherId
     * @return
     */
    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //判断一人一单
        Long userId = UserHolder.getUser().getId();

        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            //若用户已购买秒杀券，则不予再购
            return Result.fail("每人只能买一张哦！");
        }
        //5、扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
//                .eq("stock", voucher.getStock())  //乐观锁
                .gt("stock", 0)  //乐观锁改进
                .update();
        if (!success) {
            return Result.fail("秒杀失败，请稍后再试！");
        }
        //6、创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //代金券id
        voucherOrder.setVoucherId(voucherId);
        //用户id
        voucherOrder.setUserId(userId);

        save(voucherOrder);

        //7、返回订单id
        return Result.ok(orderId);
    }

}
