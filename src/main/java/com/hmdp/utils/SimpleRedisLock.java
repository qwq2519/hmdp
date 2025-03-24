package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private static final String KEY_PREFIX="lock:";
    private static final String ID_PREFIX= UUID.randomUUID().toString(true)+"-";

    private StringRedisTemplate stringRedisTemplate;
    private String name;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //集群模式下，线程Id可能冲突，因此引入UUID
        String threadId=ID_PREFIX+Thread.currentThread().getId();

        Boolean success = stringRedisTemplate.opsForValue().
                setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);

        return BooleanUtil.isTrue(success);
    }

    @Override
    public void unlock() {
        String threadId=ID_PREFIX+Thread.currentThread().getId();
        String id=stringRedisTemplate.opsForValue().get(KEY_PREFIX+name);
        if(threadId.equals(id)) {
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }
}
