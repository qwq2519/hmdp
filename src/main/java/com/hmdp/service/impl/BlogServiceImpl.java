package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Autowired
    private IUserService userService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = this.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog->{
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });

        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {

        Blog blog = this.getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }

        queryBlogUser(blog);

        isBlogLiked(blog);
        return Result.ok(blog);


    }

    private void isBlogLiked(Blog blog) {
        Long userId = UserHolder.getUser().getId();
        //使用redis判断是否已经点赞
        String redisKey = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Boolean member = stringRedisTemplate.opsForSet().isMember(redisKey, userId.toString());

        blog.setIsLike(BooleanUtil.isTrue(member));
    }

    @Override
    public Result likeBlog(Long id) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //使用redis判断是否已经点赞
        String redisKey = RedisConstants.BLOG_LIKED_KEY + id;
        Boolean member = stringRedisTemplate.opsForSet().isMember(redisKey, userId.toString());
        //如果已经点赞
        if (BooleanUtil.isTrue(member)) {
            boolean success = this.update().setSql("liked = liked -1 ").eq("id", id).update();
            if (success) {
                stringRedisTemplate.opsForSet().remove(redisKey, userId.toString());
            }
        }else {
            //如果未点赞
            boolean success = this.update().setSql("liked = liked + 1 ").eq("id", id).update();
            if (success) {
                stringRedisTemplate.opsForSet().add(redisKey, userId.toString());
            }
        }
        return Result.ok();
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
