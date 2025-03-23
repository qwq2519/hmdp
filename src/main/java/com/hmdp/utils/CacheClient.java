package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
//封装redis的string工具类
public class CacheClient {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private ReactiveStringRedisTemplate reactiveStringRedisTemplate;

    //将任意类型对象序列化为json存入redis
    public void set(String key, Object value, Long time, TimeUnit unit){
        String jsonStr = JSONUtil.toJsonStr(value);
        stringRedisTemplate.opsForValue().set(key,jsonStr,time,unit);
    }
    //将任意类型对象序列化为json存入redis,并且使用逻辑过期，处理缓存击穿
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        String jsonStr=JSONUtil.toJsonStr(redisData);
        stringRedisTemplate.opsForValue().set(key,jsonStr);
    }

    //查询id，并且反序列化为R类型，利用缓存空值处理缓存穿透
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time,TimeUnit unit){
        String key=keyPrefix+id;
        String json = stringRedisTemplate.opsForValue().get(key);

        //不为空，缓存命中
        if(StrUtil.isNotBlank(json)){
            //命中的是直接缓存的空值
            if(RedisConstants.CACHE_NULL_VALUE.equals(json)){
                return null;
            }
            //正确命中数据
            return JSONUtil.toBean(json,type);
        }

        //缓存不命中，需要查询数据库
        //查询数据库需要什么函数，交给调用方传递，
        R data=dbFallback.apply(id);

        //数据库没有，那就缓存空值
        if(data==null){
            long nullTtl=RedisConstants.CACHE_NULL_TTL+ RandomUtil.randomInt(-1,2);
            stringRedisTemplate.opsForValue().set(key,RedisConstants.CACHE_NULL_VALUE,nullTtl,TimeUnit.MINUTES);
            return null;
        }

        //数据库有，那就缓存
        this.set(key,data,time,unit);

        return data;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    //查询，并且反序列化为R类型，利用逻辑过期处理缓存击穿
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key=keyPrefix+id;
        String json =stringRedisTemplate.opsForValue().get(key);

        //空数据
        if(StrUtil.isBlank(json)) {
            return null;
        }

        //命中缓存，反序列化为对象
        RedisData redisData=JSONUtil.toBean(json,RedisData.class);
        R data= JSONUtil.toBean((JSONObject) redisData.getData(),type);
        LocalDateTime expireTime = redisData.getExpireTime();

        //未过期
        if(expireTime.isAfter(LocalDateTime.now())){
            return data;
        }

        //过期了,需要缓存重建

        String lockKey=RedisConstants.LOCK_SHOP_KEY+id;
        boolean getLock = tryLock(lockKey);

        if(getLock){
            //抢锁成功

            try {
                //做double check  二次检查缓存
                String jsonAgain=stringRedisTemplate.opsForValue().get(key);
                if(StrUtil.isNotBlank(jsonAgain)){
                    //缓存命中，说明其他线程重建了
                    RedisData redisAgain=JSONUtil.toBean(jsonAgain,RedisData.class);
                    if(redisAgain.getExpireTime().isAfter(LocalDateTime.now())){
                        unLock(lockKey);
                        return JSONUtil.toBean((JSONObject) redisAgain.getData(),type);
                    }
                }

                //缓存没有命中，该线程需要新建一个线程来实现缓存重建

                CACHE_REBUILD_EXECUTOR.submit(()->{
                    try {
                        //查询数据库
                        R r1= dbFallback.apply(id);
                        //写入redis
                        this.setWithLogicalExpire(key,r1,time,unit);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        unLock(lockKey);
                    }
                });
            } catch (Exception e) {
                unLock(lockKey);
                throw new RuntimeException(e);
            }
        }
        //没抢到，说明其他线程去更新了
        //返回过期信息
        return data;
    }

    public <R,ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        //从redis中查询
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        //缓存命中，但是是空数据，是之前缓存的空字符串，表示是之前穿透过的
        if (RedisConstants.CACHE_NULL_VALUE.equals(json)) {
            return null;
        }

        //缓存命中，且是有效数据
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }

        //什么都没有命中，那就去数据库查找,实现缓存重构
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        R r=null;
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
                String jsonAgain = stringRedisTemplate.opsForValue().get(key);
                if (StrUtil.isNotBlank(jsonAgain)) {
                    return JSONUtil.toBean(jsonAgain, type);
                }

                //查询数据库
                r=dbFallback.apply(id);

                //数据库也没有
                if (r == null) {
                    Long nullTTL = RedisConstants.CACHE_NULL_TTL + RandomUtil.randomInt(-1, 2);
                    //缓存空对象
                    stringRedisTemplate.opsForValue().set(key, RedisConstants.CACHE_NULL_VALUE,
                            nullTTL, TimeUnit.MINUTES);
                    return null;
                }
                //写入redis
                this.set(key,r,time,unit);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unLock(lockKey);
        }
    }


    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

}
