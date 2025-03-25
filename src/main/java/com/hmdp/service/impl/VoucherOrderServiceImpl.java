package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;

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

    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");

        //执行脚本并获得反应结果
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        //TODO 加入阻塞队列

        //返回订单id
        return Result.ok(orderId);
    }


    /*@Override
    public Result seckillVoucher(Long voucherId) {
        //查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        LocalDateTime now = LocalDateTime.now();
        //还没开始
        if (now.isBefore(voucher.getBeginTime())) {
            return Result.fail("秒杀尚未开始！");
        }

        //结束了
        if (now.isAfter(voucher.getEndTime())) {
            return Result.fail("秒杀已经结束！");
        }

        //库存不足
        if (voucher.getStock() <= 0) {
            return Result.fail("库存不足！");
        }

        Long userId=UserHolder.getUser().getId();

        //创建锁对象 现在使用Redisson
        //SimpleRedisLock lock=new SimpleRedisLock("order:"+userId,stringRedisTemplate);

        RLock lock = redissonClient.getLock("lock:order:" + userId);

        //尝试加锁
        boolean isLock= lock.tryLock();

        if(!isLock){
            return Result.fail("不允许重复下单");
        }else{
            try {
                //获取代理对象（事务)
                IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
                return proxy.createVoucherOrder(voucherId);
            } finally {
                lock.unlock();
            }
        }


        //为什么在这里加锁？
        //下面的方法需要事务管理，因此为了线程安全，还是要给整个方法加锁，不然同步块结束可能spring事务还没提交，导致并发安全问题
        synchronized (userId.toString().intern()) {//返回字符串在常量池（String Pool） 中的唯一引用。
//            这里是spring事务失效的几种场景
//            return this.createVoucherOrder(voucherId);
            //我们需要拿到spring的代理对象，调用它的方法才行
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }

    }*/

    @Transactional
    @Override
    public Result createVoucherOrder(Long voucherId) {
        //一人一单逻辑
        Long userId = UserHolder.getUser().getId();

        int count = this.query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            //抢过优惠券了
            return Result.fail("用户已经购买过一次！");
        }

        //减少库存 这是两种乐观锁的实现
        //下面这种乐观锁实现方式效率比较差，虽然安全，但是会导致卖出的券很少，因为卖出的概率很低
//        boolean success = seckillVoucherService.update()
//                .setSql("stock = stock -1")
//                .eq("voucher_id", voucherId).eq("stock",voucher.getStock()).update();
        //大于0就可以卖
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherId).gt("stock", 0).update();


        if (!success) {
            return Result.fail("库存不足！");
        }

        //生成优惠券订单

        long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = VoucherOrder.builder()
                .id(orderId)
                .userId(userId)
                .voucherId(voucherId)
                .build();

        this.save(voucherOrder);

        return Result.ok(orderId);

    }
}
