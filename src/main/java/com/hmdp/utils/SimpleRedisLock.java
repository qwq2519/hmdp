package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    public static final String KEY_PREFIX="lock:";

    private StringRedisTemplate stringRedisTemplate;
    private String name;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        long threadId = Thread.currentThread().getId();

        Boolean success = stringRedisTemplate.opsForValue().
                setIfAbsent(KEY_PREFIX + name, threadId + "", timeoutSec, TimeUnit.SECONDS);

        return BooleanUtil.isTrue(success);
    }

    @Override
    public void unlock() {
        stringRedisTemplate.delete(KEY_PREFIX+name);
    }
}
