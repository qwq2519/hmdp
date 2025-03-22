package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> listAll() {

        //查询缓存
        String redisKey= RedisConstants.CACHE_SHOP_LIST;
        String listJson = stringRedisTemplate.opsForValue().get(redisKey);

        if(StrUtil.isNotBlank(listJson)){
            List<ShopType> typeListlist = JSONUtil.toList(listJson, ShopType.class);
            return typeListlist;
        }
        //缓存不存在,去数据库查找
        List<ShopType> typeList = this.query().orderByAsc("sort").list();

        stringRedisTemplate.opsForValue().set(redisKey,JSONUtil.toJsonStr(typeList));

        return typeList;
    }
}
