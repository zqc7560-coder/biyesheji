package com.blog.module.tag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blog.module.tag.entity.Tag;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 标签 Mapper（M4 补齐治理统计；M7 的 AI 标签会先查这里做 existingTagIds 复用）。 */
public interface TagMapper extends BaseMapper<Tag> {

    /**
     * 标签的"真实引用数"：article_tag 物理关联 JOIN 未删除文章。
     *
     * <p>口径必须与 M3 的 bumpTagCount 完全一致——rebuildTags 对草稿/下线文章同样
     * +/-（标签是文章属性而非发布态属性），所以这里只过滤 a.deleted=0、**不过滤
     * status**；若误加 status=1，合并后的重算会把草稿引用清零，账实立刻不符。
     *
     * <p>为什么合并/删除后要重算冗余列：合并是"跨文章批量搬移"，逐个 +/- 一旦
     * 中途失败就漂移；搬移完成后按事实表 COUNT 重算，冗余列自我修复。
     */
    @Select("""
            SELECT COUNT(*) FROM article_tag lt
            JOIN article a ON a.id = lt.article_id AND a.deleted = 0
            WHERE lt.tag_id = #{tagId}
            """)
    long countLiveRef(@Param("tagId") Long tagId);
}
