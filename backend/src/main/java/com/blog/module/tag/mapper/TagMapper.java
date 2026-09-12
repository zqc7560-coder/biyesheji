package com.blog.module.tag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blog.module.tag.entity.Tag;

/** 标签 Mapper（M4/M7 使用；M7 的 AI 标签会先查这里做 existingTagIds 复用）。 */
public interface TagMapper extends BaseMapper<Tag> {
}
