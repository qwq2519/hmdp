package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
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
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

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

    //异步处理线程池
    public static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    IVoucherOrderService proxy;

    //在类初始化之后执行，因为当这个类初始化好了之后，随时都是有可能要执行的
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    //获取消息队列中的订单信息  XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                    );

                    //判断是否有消息
                    if(CollectionUtil.isEmpty(list)){
                        continue;
                    }

                    //解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value,new VoucherOrder() ,true);
                    //创建订单
                    createVoucherOrder(voucherOrder);
                    //确认消息
                    stringRedisTemplate.opsForStream().acknowledge("s1","g1",record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList(){
            while(true){
                try {
                    //获取PendingList中的订单信息  XREADGROUP GROUP g1 c1 COUNT 1  STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create("stream.orders", ReadOffset.from("0"))
                    );

                    //判断是否有消息
                    if(CollectionUtil.isEmpty(list)){
                        break;
                    }

                    //解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value,new VoucherOrder(),true);
                    //创建订单
                    createVoucherOrder(voucherOrder);
                    //确认消息
                    stringRedisTemplate.opsForStream().acknowledge("s1","g1",record.getId());
                } catch (Exception e) {
                    log.error("处理pendinglist订单异常",e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }


    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");

        //执行脚本并获得反应结果,这里通过lua脚本加入到redis消息队列了
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //获取当前代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
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
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单逻辑
        Long userId =voucherOrder.getUserId();

        int count = this.query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            //抢过优惠券了
            log.error("用户已经购买过一次！");
            return;
        }

        //减少库存 这是两种乐观锁的实现
        //下面这种乐观锁实现方式效率比较差，虽然安全，但是会导致卖出的券很少，因为卖出的概率很低
//        boolean success = seckillVoucherService.update()
//                .setSql("stock = stock -1")
//                .eq("voucher_id", voucherId).eq("stock",voucher.getStock()).update();
        //大于0就可以卖
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0).update();


        if (!success) {
            log.error("库存不足！");
            return;
        }

        this.save(voucherOrder);


    }
}
