package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
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
    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
//    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
//
//    @PostConstruct
//    private void init() {
//        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrerHandler());
//    }
//
//    private class VoucherOrerHandler implements Runnable {
//
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    //1、获取队列中的订单信息
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    //2、创建订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("处理订单异常：" + e);
//                }
//            }
//        }
//    }
//
//    private void handleVoucherOrder(VoucherOrder voucherOrder) {
//        //使用redisson的方法创建锁
//        RLock rLock = redissonClient.getLock("order:" + voucherOrder.getUserId());
//        //获取锁，并判断获取锁是否成功
//        boolean isLock = rLock.tryLock();
//        if (!isLock) {
//            //获取锁失败
//            log.error("不允许重复下单");
//            return;
//        }
//        try {
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            rLock.unlock();
//        }
//    }
//
//    private IVoucherOrderService proxy;

    /**
     * 秒杀券下单
     *
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

//        //将券的库存和订单保存在redis，判断用户是否有秒杀资格
//        //1、执行lua脚本
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(),
//                userId.toString()
//        );
//        //2、判断脚本结果，1：秒杀券库存不足，2：重复下单
//        int r = result.intValue();
//        if (result != 0) {
//            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
//        }
//        //判断脚本结果，0：有购买资格，把下单信息保存到阻塞队列
//        VoucherOrder voucherOrder = new VoucherOrder();
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(voucherId);
//        voucherOrder.setVoucherId(voucherId);
//        //保存到消息队列
//        orderTasks.add(voucherOrder);
//        //3、获取代理对象，放到队列里
//        //获取事务有关的代理对象，这样只有当事务提交后才会放开锁，避免事务没提交就释放锁的情况
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        return Result.ok(orderId);

        //通过调取数据库的方式，查询用户是否有秒杀权限
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
        /*
         * 以下注释部分是采用自定义的分布式锁
         * 非注释部分采用的是redisson封装好的方法创建锁
         */
        //创建分布式锁对象
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        //使用redisson的方法创建锁
        RLock rLock = redissonClient.getLock("order:" + userId);

        //获取锁，并判断获取锁是否成功
//        boolean isLock = lock.tryLock(1200);
        boolean isLock = rLock.tryLock();
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
//            lock.unlock();
            rLock.unlock();
        }
//        //单体锁（非分布式锁）
//        synchronized (userId.toString().intern()) {
//            //获取事务有关的代理对象，这样只有当事务提交后才会放开锁，避免事务没提交就释放锁的情况
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }
    }

    /**
     * 创建订单，实现”一人一单“
     *
     * @param voucherId
     * @return
     */
    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //判断一人一单
        Long userId = UserHolder.getUser().getId();
        //查询用户是否购买过券，若购买过券，则不予再购
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("每人只能买一张哦！");
        }
        //若没购买过，则扣减库存
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
