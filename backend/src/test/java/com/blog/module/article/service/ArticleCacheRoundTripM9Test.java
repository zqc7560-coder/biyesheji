package com.blog.module.article.service;

import com.blog.module.article.vo.ArticleVO;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 详情缓存载荷的 Jackson 回环单测（M9）——blog:article:{id} 存的就是这坨 JSON，
 * "序列化形状 == 反序列化形状"若被破坏（@Builder + @JsonDeserialize(builder) 组合是
 * 经典翻车点），缓存命中路径会静默回吐一个字段全空的 VO（NON_NULL 下 data={}），
 * 接口层表现为"文章没了标题"。这个测试钉的是缓存体系的载荷契约，不碰 Spring。
 */
class ArticleCacheRoundTripM9Test {

    /** 复刻应用侧 ObjectMapper 配置（Spring Boot 默认 + 本项目的 NON_NULL 约定） */
    private ObjectMapper appLikeMapper() {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        om.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return om;
    }

    private ArticleVO sample() {
        return ArticleVO.builder()
                .id(42L).userId(1L).title("回环验证：中文标题与 Markdown 符号 ** _ `")
                .contentMd("# h1\n\nsnake_case_name 与 **粗体**")
                .summary("摘要").summarySource(1)
                .viewCount(7L).likeCount(0).commentCount(3)
                .status(1).allowComment(1)
                .publishedAt(LocalDateTime.of(2026, 9, 13, 8, 30, 0))
                .createdAt(LocalDateTime.of(2026, 9, 12, 23, 0, 0))
                .updatedAt(LocalDateTime.of(2026, 9, 13, 8, 0, 0))
                .version(2)
                .category(ArticleVO.CategoryBrief.builder().id(1L).name("技术").build())
                .author(ArticleVO.AuthorBrief.builder().id(1L).nickname("测试用户").build())
                .tags(List.of(ArticleVO.TagBrief.builder().id(5L).name("SpringBoot").build()))
                .build();
    }

    @Test
    @DisplayName("VO→JSON→VO 全字段回环：builder 反序列化不吞任何字段（含嵌套 brief 与 LocalDateTime）")
    void fullRoundTrip() throws Exception {
        ObjectMapper om = appLikeMapper();
        String json = om.writeValueAsString(sample());
        ArticleVO back = om.readValue(json, ArticleVO.class);

        // 逐个钉关键维度：标量 / 时间 / 嵌套对象 / 列表——builder 吞字段往往只吞嵌套
        assertThat(back.getId()).isEqualTo(42L);
        assertThat(back.getTitle()).startsWith("回环验证：中文标题");
        assertThat(back.getVersion()).isEqualTo(2);
        assertThat(back.getPublishedAt()).isEqualTo(LocalDateTime.of(2026, 9, 13, 8, 30, 0));
        assertThat(back.getCategory()).isNotNull();
        assertThat(back.getCategory().getName()).isEqualTo("技术");
        assertThat(back.getAuthor().getNickname()).isEqualTo("测试用户");
        assertThat(back.getTags()).hasSize(1);
        assertThat(back.getTags().get(0).getName()).isEqualTo("SpringBoot");
    }

    @Test
    @DisplayName("二次回环幂等：回读对象再序列化与原 JSON 字符串级相等（缓存键值稳定性的根基）")
    void roundTripIsStable() throws Exception {
        ObjectMapper om = appLikeMapper();
        String j1 = om.writeValueAsString(sample());
        String j2 = om.writeValueAsString(om.readValue(j1, ArticleVO.class));
        assertThat(j2).isEqualTo(j1);
    }
}
