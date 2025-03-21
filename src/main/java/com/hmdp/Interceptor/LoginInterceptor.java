package com.hmdp.Interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import com.hmdp.entity.User;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

public class LoginInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    //构造器注入，这个login拦截器不是spring管理的，不能使用注解注入
    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        /**
        HttpSession session = request.getSession();

        UserDTO user = (UserDTO) session.getAttribute("user");

        //用户不存在
        if(user==null){
            response.setStatus(401);
             return false;
        }

        //存在
        UserHolder.saveUser(user);
        */

        //获取token
        String token = request.getHeader("authorization");

        if(StrUtil.isBlank(token)){
            response.setStatus(401);
            return false;
        }

        //在redis中查询对象，并且刷新redis中的数据，说明用户在活跃，不要清除数据

        String tokenKey=LOGIN_USER_KEY+token;
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(tokenKey);

        if(entries.isEmpty()){
            response.setStatus(401);
            return false;
        }

        UserDTO userDTO = BeanUtil.fillBeanWithMap(entries, new UserDTO(), false);
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        //存入threadlocal
        UserHolder.saveUser(userDTO);




        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
