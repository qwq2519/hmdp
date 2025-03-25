package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private CacheClient cacheClient;



    @Override
    public Result queryById(Long id) {
//        缓存穿透
//      Shop shop=queryWithPassThrough(id);
        Shop shop=cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY,id,Shop.class,this::getById,
                RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
//                        Shop shop =cacheClient.queryWithMutex(RedisConstants.CACHE_SHOP_KEY,id,Shop.class,this::getById,
//                20L,TimeUnit.SECONDS);

        //逻辑过期解决缓存击穿
//        Shop shop = queryWithLogicalExpire(id);
//        Shop shop =cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY,id,Shop.class,this::getById,
//                RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //测试用
//                Shop shop =cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY,id,Shop.class,this::getById,
//                20L,TimeUnit.SECONDS);
//        Shop shop=null;
        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }

    /*
    //互斥锁解决缓存击穿
    public Shop queryWithMutex(Long id) {
        //从redis中查询
        String redisKey = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(redisKey);

        //缓存命中，但是是空数据，是之前缓存的空字符串，表示是之前穿透过的
        if (RedisConstants.CACHE_NULL_VALUE.equals(shopJson)) {
            return null;
        }

        //缓存命中，且是有效数据
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        //什么都没有命中，那就去数据库查找,实现缓存重构
        Shop shop = null;
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;

        try {
            //获取互斥锁，使用循环代替递归
            while (true) {
                boolean getLock = tryLock(lockKey);
                //获取失败，休眠重试
                if (!getLock) {
                    Thread.sleep(50);
                    continue;
                }
                //二次检查缓存
                shopJson = stringRedisTemplate.opsForValue().get(redisKey);
                if (StrUtil.isNotBlank(shopJson)) {
                    return JSONUtil.toBean(shopJson, Shop.class);
                }

                //查询数据库
                shop = getById(id);

                //为了压测，休眠，模拟一个长时间的缓存重建
                Thread.sleep(200);

                //数据库也没有
                if (shop == null) {
                    Long nullTTL = RedisConstants.CACHE_NULL_TTL + RandomUtil.randomInt(-1, 2);
                    //缓存空对象
                    stringRedisTemplate.opsForValue().set(redisKey, RedisConstants.CACHE_NULL_VALUE,
                            nullTTL, TimeUnit.MINUTES);
                    return null;
                }
                //写入redis
                Long shopTTL = RedisConstants.CACHE_SHOP_TTL + RandomUtil.randomInt(-5, 5);
                stringRedisTemplate.opsForValue().set(redisKey, JSONUtil.toJsonStr(shop),
                        shopTTL, TimeUnit.MINUTES);
                return shop;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unLock(lockKey);
        }

    }
    */

/**
    //利用单元测试添加缓存，进行缓存预热
    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        //查询数据
        Shop shop = getById(id);

        //模拟延迟
        Thread.sleep(200);


        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
    */


  /**
   * 解缓存击穿
   private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

   public Shop queryWithLogicalExpire(Long id) {

        //热点key，一般都会进行缓存预热，不需要考虑缓存穿透什么的

        //从redis中查询带有逻辑过期时间的数据
        String redisKey = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(redisKey);

        //缓存未命中
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }

        //缓存命中，将其反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject jsonObject = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(jsonObject, Shop.class);

        LocalDateTime expireTime = redisData.getExpireTime();

        if (expireTime.isAfter(LocalDateTime.now())) {
            //没有过期，直接返回
            return shop;
        }

        //过期了，需要缓存重建，重新获取数据，设置过期时间
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;

        if (tryLock(lockKey)) {
            try {
                //获取锁成功,新开一个线程去异步重建缓存
                //Double Check 二次检查缓存
                String shopJsonAgain = stringRedisTemplate.opsForValue().get(redisKey);
                if (StrUtil.isNotBlank(shopJsonAgain)) {
                    //缓存命中了,说明其他线程重建了

                    RedisData redisDataAgain = JSONUtil.toBean(shopJsonAgain, RedisData.class);
                    LocalDateTime expireTimeAgain = redisDataAgain.getExpireTime();
                    if (expireTimeAgain.isAfter(LocalDateTime.now())) {
                        // 缓存已重建，释放锁并返回新数据
                        unLock(lockKey);
                        JSONObject dataJsonAgain = (JSONObject) redisDataAgain.getData();
                        return JSONUtil.toBean(dataJsonAgain, Shop.class);
                    }
                }
                //提交异步任务，重建缓存
                CACHE_REBUILD_EXECUTOR.submit(
                        () -> {
                            try {
                                this.saveShop2Redis(id, 20L);
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            } finally {//一定要释放锁
                                unLock(lockKey);
                            }
                        });
            } catch(Exception e) {
                unLock(lockKey);
                throw new RuntimeException(e);
            }
        }

        //返回过期的店铺信息
        return shop;
    }
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
*/

    /* //解决缓存穿透
    public Shop queryWithPassThrough(Long id) {
        //从redis中查询
        String redisKey = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(redisKey);


        //缓存命中，但是是空数据，是之前缓存的空字符串，表示是之前穿透过的
        if (RedisConstants.CACHE_NULL_VALUE.equals(shopJson)) {
            return null;
        }

        //缓存命中，且是有效数据
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        //什么都没有命中，那就去数据库查找
        //redis没有缓存，那就去数据库查找
        Shop shop = null;

        shop = getById(id);

        //数据库也没有
        if (shop == null) {
            Long nullTTL = RedisConstants.CACHE_NULL_TTL + RandomUtil.randomInt(-1, 2);
            //缓存空对象
            stringRedisTemplate.opsForValue().set(redisKey, RedisConstants.CACHE_NULL_VALUE,
                    nullTTL, TimeUnit.MINUTES);
            return null;
        }
        Long shopTTL = RedisConstants.CACHE_SHOP_TTL + RandomUtil.randomInt(-5, 5);
        stringRedisTemplate.opsForValue().set(redisKey, JSONUtil.toJsonStr(shop),
                shopTTL, TimeUnit.MINUTES);

        return shop;
    }
*/

    @Transactional
    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("id不能为空");
        }

        //先操作数据库
        this.updateById(shop);

        //再删除缓存
        String redisKey = RedisConstants.CACHE_SHOP_KEY + id;
        stringRedisTemplate.delete(redisKey);
        return Result.ok();
    }
}
