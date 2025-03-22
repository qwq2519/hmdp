package com.hmdp.service.impl;

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

import java.io.Serializable;

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

        if (StrUtil.isNotBlank(shopJson)) {
            shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }

        //redis没有缓存，那就去数据库查找
        shop = getById(id);

        //数据库也没有
        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        stringRedisTemplate.opsForValue().set(redisKey, JSONUtil.toJsonStr(shop));

        return Result.ok(shop);
    }
}
