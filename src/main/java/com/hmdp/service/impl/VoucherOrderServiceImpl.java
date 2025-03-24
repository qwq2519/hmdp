package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Transactional
    @Override
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
