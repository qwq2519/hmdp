package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.math.BitStatusUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.toolkit.BeanUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

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
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误！");
        }

        String code = RandomUtil.randomNumbers(6);

        //将手机号:验证码保存到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);//set key value ex

//        //将验证码和手机保存到session中
//        session.setAttribute("code",code);

        log.debug("发送短信验证码成功，验证码：{}", code);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();

        //校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误！");
        }

        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);

        //超时了或者有误
        if(code==null||!code.equals(loginForm.getCode())){
            return Result.fail("验证码错误");
        }

//        String sessionCode =(String) session.getAttribute("code");
//        String code1 = loginForm.getCode();
//
//        //验证码有误
//        if(sessionCode==null||!sessionCode.equals(code1)){
//            return Result.fail("验证码错误");
//        }

        User user = query().eq("phone", phone).one();


        if(user==null){
            user=userCreateWithPhone(phone);
        }
//        session.setAttribute("user",BeanUtil.copyProperties(user,UserDTO.class));

        //生成一个token，作为用户登录的令牌
        String token= UUID.randomUUID().toString(true);

        //存到redis
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String,Object> userMap=BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString())
                );

        String tokenKey=LOGIN_USER_KEY+token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);

        return Result.ok(token);
    }

    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();

        String keySuffix=now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String redisKey= USER_SIGN_KEY+userId+keySuffix;

        int day = now.getDayOfMonth();

        stringRedisTemplate.opsForValue().setBit(redisKey,day-1,true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();

        String keySuffix=now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String redisKey= USER_SIGN_KEY+userId+keySuffix;

        int day = now.getDayOfMonth();

        List<Long> result = stringRedisTemplate.opsForValue().bitField(redisKey,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(day))
                        .valueAt(0));
        if(CollectionUtil.isEmpty(result)){
            return Result.ok(0);
        }
        Long num = result.get(0);
        if(num==null){
            return Result.ok(0);
        }
        int count=0;
        while (true){
            if((num&1)==0){
                break;
            }else{
                ++count;
            }
            num>>>=1;
        }
        return Result.ok(count);
    }

    private User userCreateWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
