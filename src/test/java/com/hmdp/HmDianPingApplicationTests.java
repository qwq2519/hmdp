package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.*;

@SpringBootTest
class HmDianPingApplicationTests {

    @Autowired
    private ShopServiceImpl shopService;

    @Autowired
    private CacheClient cacheClient;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private RedissonClient redisClient;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void IdWorkerTest() throws Exception {
        CountDownLatch latch = new CountDownLatch(300);

        Runnable Task = () -> {
            for (int i = 0; i < 100; ++i) {
                long id = redisIdWorker.nextId("order");
//                System.out.println("id = "+id);
            }
            latch.countDown();
        };

        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; ++i) {
            es.submit(Task);
        }
        latch.await();
        long end = System.currentTimeMillis();

        System.out.println("costTime = "+(end - begin));
    }

    @Test
    void redissonTest() throws Exception{
        //获取可重入锁
        RLock lock = redisClient.getLock("anyLock");
        //尝试获取锁
        boolean isLock=lock.tryLock(1,10,TimeUnit.SECONDS);
        //判断是否成功
        if(isLock){
            try {
                System.out.println("执行业务");
            } finally {
                lock.unlock();
            }
        }
    }

}
