package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

@SpringBootTest
public class UserLoginBatch {

    @Autowired
    IUserService userService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    public void generateBatchUserTokens() {
        List<User> users = userService.list();

        File file = new File("src/main/resources/token.txt");

        try (FileOutputStream output = new FileOutputStream(file, false)) { // false to overwrite
            for (int i = 0; i < users.size(); i++) { // Process all users
                User user = users.get(i);

                // Generate token
                String token = UUID.randomUUID().toString(true);

                // Convert to UserDTO
                UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

                // Write token to file
                output.write(token.getBytes());
                output.write("\r\n".getBytes());

                // Store in Redis
                Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                        CopyOptions.create()
                                .setIgnoreNullValue(true)
                                .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

                String tokenKey = LOGIN_USER_KEY + token;
                stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
                stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write tokens to file", e);
        }
    }


}
