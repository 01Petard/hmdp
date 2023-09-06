package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IBlogService blogService;
    @Resource
    private IUserService userService;
    @Resource
    private IFollowService followService;

    /**
     * 保存博客
     * @param blog
     * @return
     */
    @Override
    public Result saveBlog(Blog blog) {
        //设置博客用户id
        Long userId = UserHolder.getUser().getId();
        blog.setUserId(userId);
        boolean isSuccess = blogService.save(blog);
        if (!isSuccess) {
            return Result.ok("新增博客失败！");
        }
        List<Follow> follows = followService.query().eq("follow_user_id", userId).list();
        for (Follow follow : follows) {
            //推送
            String key = FEED_KEY + follow.getUserId();
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }

        // 返回博客id
        return Result.ok(blog.getId());
    }

    /**
     * 查询当前用户的博客
     * @param current
     * @return
     */
    @Override
    public Result queryMyBlog(Integer current) {
        // 根据用户id查询
        Page<Blog> page = blogService.query().eq("user_id", UserHolder.getUser().getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    /**
     * 查询前10篇热门博客
     * @param current
     * @return
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    /**
     * 查询博客
     * @param id
     * @return
     */
    @Override
    public Result queryBlogById(Long id) {
        //查询blog
        Blog blog = blogService.getById(id);
        if (blog == null) {
            return Result.fail("笔记不不在！");
        }
        //查询blog有关的用户
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
        //查询blog是否被当前用户点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    /**
     * 查询blog是否被当前用户点赞
     * @param blog
     */
    private void isBlogLiked(Blog blog) {
        //若用户未登录，无需查询是否点赞
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return;
        }
        //1、获取登录用户，判断用户是否点赞
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();

        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    /**
     * 博客点赞、取消点赞
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        //1、获取登录用户，判断用户是否点赞
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Long userId = UserHolder.getUser().getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            //2、如果未点赞，数据库点赞加一，缓存用户到redis的set中
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            //3、如果已点赞，数据库点赞减一，删除redis中set的用户
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查看给博客点赞的前5个人
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        //1、查询前5个点赞用户
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //2、解析用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        //根据用户id查询用户
        List<UserDTO> userDTOs = userService.query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOs);
    }

    /**
     * 根据用户id查询blog
     * @param current
     * @param id
     * @return
     */
    @Override
    public Result queryBlogByUserId(Integer current, Long id) {
        // 根据用户查询
        Page<Blog> page = blogService.query().eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    /**
     * 滚动分页查询
     * @param max
     * @param offset
     * @return
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        // 3.非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 4.解析数据：blogId、minTime（时间戳）、offset，获取需要查询的blog的id
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0; //最后一篇blog的时间戳
        int timeSameCount = 1; //统计时间戳相同的blog
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) { // 5 4 4 2 2
            // 4.1.获取id
            ids.add(Long.valueOf(tuple.getValue()));
            // 4.2.获取分数(时间戳）
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                timeSameCount++;
            } else {
                timeSameCount = 1;
            }
            minTime = time;
        }
        // 5.根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Blog blog : blogs) {
            // 5.1.查询blog有关的用户
            queryBlogUser(blog);
            // 5.2.查询blog是否被点赞
            isBlogLiked(blog);
        }
        // 6.封装并返回
        ScrollResult sr = new ScrollResult();
        sr.setList(blogs);
        sr.setOffset(timeSameCount);
        sr.setMinTime(minTime);
        return Result.ok(sr);
    }


}
