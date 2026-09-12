package com.blog.module.system.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 系统配置批量修改载荷（M8）—— 请求体形态逐字对齐《API接口设计.md》§8：
 * PUT /api/admin/system/config  { "items": [ { "key": "...", "value": "..." } ] }
 *
 * <p>为什么批量而不是单键 PUT：站点参数是一个"表单"，管理员改完一起提交；
 * 逐键请求会让"改了一半关页面"成为常态脏状态。批量语义下 Service 在单事务内
 * 整批校验+整批落库，失败零部分生效。
 *
 * <p>value 用 String 而非 Object：sys_config.config_value 列就是 VARCHAR(500)
 * 统一存标量/JSON 字符串（《数据库设计.md》§3.14），类型解释靠 config_type 列，
 * DTO 不越权做类型转换——类型校验在 Service 按库中实际 config_type 执行才可信
 * （客户端自报类型是伪造面）。
 */
@Data
public class SystemConfigUpdateDTO {

    /** 待修改项：至少一条（空数组批量修改是误操作信号，直接 400 比静默成功诚实） */
    @NotEmpty(message = "items 不能为空")
    @Valid
    private List<Item> items;

    /** 单条键值对 */
    @Data
    public static class Item {
        /** 配置键：必须已在 sys_config 中存在（不存在→5001），本期不支持后台新增键 */
        @NotBlank(message = "配置键不能为空")
        @Size(max = 80, message = "配置键超长")
        private String key;

        /** 配置值：统一字符串（boolean 存 "true"/"false"，number 存十进制串） */
        @NotBlank(message = "配置值不能为空")
        @Size(max = 500, message = "配置值超过 500 字符")
        private String value;
    }
}
