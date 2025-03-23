package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        //从redis中查询
        String redisKey = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(redisKey);

        Shop shop = null;

        //缓存命中，但是是空数据，是之前缓存的空字符串，表示是之前穿透过的
        if(RedisConstants.CACHE_NULL_VALUE.equals(shopJson)){
            return Result.fail("店铺不存在");
        }

        //缓存命中，且是有效数据
        if (StrUtil.isNotBlank(shopJson)) {
            shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }

        //什么都没有命中，那就去数据库查找
        //redis没有缓存，那就去数据库查找
        shop = getById(id);

        //数据库也没有
        if (shop == null) {
            Long nullTTL=RedisConstants.CACHE_NULL_TTL+ RandomUtil.randomInt(-1,2);
            //缓存空对象
            stringRedisTemplate.opsForValue().set(redisKey,RedisConstants.CACHE_NULL_VALUE,
                    nullTTL , TimeUnit.MINUTES);

            return Result.fail("店铺不存在");
        }
        Long shopTTL=RedisConstants.CACHE_SHOP_TTL+RandomUtil.randomInt(-5,5);
        stringRedisTemplate.opsForValue().set(redisKey, JSONUtil.toJsonStr(shop),
               shopTTL , TimeUnit.MINUTES);

        return Result.ok(shop);
    }

    @Transactional
    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id==null){
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
