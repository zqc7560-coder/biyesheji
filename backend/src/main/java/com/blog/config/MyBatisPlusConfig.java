package com.blog.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件装配 —— 对应《系统设计.md》§4 选型理由中
 * "分页插件、逻辑删除、乐观锁内置"三点。
 *
 * <p>插件顺序有讲究：分页拦截器必须放在最后修改 SQL 的环节之前，
 * 且多个内部拦截器按 addInnerInterceptor 的注册顺序执行。
 */
@Configuration
public class MyBatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 1) 乐观锁：article.version 字段并发编辑防覆盖（TC-F-04 用例的底层支撑）。
        //    实体加 @Version 后，update 自动拼 "SET version=version+1 WHERE version=旧值"，
        //    影响 0 行即代表冲突，Service 层据此抛 2004。
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        // 2) 防全表更新/删除：拦住手滑的 "UPDATE article SET status=0" 不带 WHERE 的低级事故，
        //    对毕业设计同样值得防——答辩现场演示误操作恢复成本高。
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        // 3) 分页：MySQL 方言。maxLimit=100 落实 API 设计 §1.2 的 pageSize 上限约定，
        //    防止前端传 pageSize=999999 拖垮数据库。
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(100L);
        pagination.setOverflow(false); // 超页返回空列表而非回到首页，语义更符合 REST 直觉
        interceptor.addInnerInterceptor(pagination);

        return interceptor;
    }
}
