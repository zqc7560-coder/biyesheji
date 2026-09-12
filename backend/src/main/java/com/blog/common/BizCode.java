package com.blog.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 业务错误码枚举 —— 与《API接口设计.md》§1.4 错误码表逐条对齐。
 *
 * <p>为什么要枚举区段：0 通用 / 1xxx 认证用户 / 2xxx 内容 / 3xxx 文件 / 4xxx AI / 5xxx 配置。
 * 前端拦截器按区段做统一动作（如 1001/1004 跳登录页），业务码细分到具体场景，
 * 两套语义互不干扰；新增错误码必须同步更新 API 设计文档，保持契约一致。
 */
@Getter
@AllArgsConstructor
public enum BizCode {

    /** 成功：统一响应体的默认码 */
    SUCCESS(0, "success"),

    /** 系统内部异常兜底：日志记全栈，响应不回传堆栈（防信息泄露） */
    SYSTEM_ERROR(500, "系统内部异常"),

    /** 参数校验失败：data 携带字段级明细 */
    PARAM_ERROR(400, "参数校验失败"),

    // ---------- 认证 / 授权（1xxx） ----------
    /** 未认证或 Token 失效/已登出（HTTP 401） */
    UNAUTHORIZED(1001, "未认证或令牌失效"),
    /** 用户名或密码错误：不区分"用户不存在"，防账号枚举（HTTP 401） */
    LOGIN_FAILED(1002, "用户名或密码错误"),
    /** 账号被禁用（HTTP 403） */
    ACCOUNT_DISABLED(1003, "账号已被禁用"),
    /** Token 过期，前端据此静默刷新或跳登录（HTTP 401） */
    TOKEN_EXPIRED(1004, "Token 已过期"),
    /** RBAC / 属主校验失败（HTTP 403） */
    FORBIDDEN(1005, "无权限访问该资源"),

    // ---------- 用户（11xx） ----------
    USERNAME_EXISTS(1101, "用户名已存在"),
    EMAIL_EXISTS(1102, "邮箱已被注册"),

    // ---------- 文章（20xx） ----------
    ARTICLE_NOT_FOUND(2001, "文章不存在或已删除"),
    ARTICLE_NOT_OWNER(2002, "无权操作他人文章"),
    CATEGORY_NOT_FOUND(2003, "分类不存在"),
    /** 乐观锁冲突：多人/多标签页编辑同一文章时的防覆盖信号 */
    VERSION_CONFLICT(2004, "内容已被他人更新，请刷新后重试"),

    // ---------- 分类 / 标签 / 评论（21xx~23xx） ----------
    CATEGORY_NOT_EMPTY(2101, "分类下仍有文章，禁止删除"),
    /** M4：两级约束的延伸护栏——子分类存在时禁止删除/挂接，错误码区段顺延（设计文档未单列，走 21xx 家族） */
    CATEGORY_HAS_CHILDREN(2102, "存在子分类，禁止删除或挂接"),
    /** M4：分类重名（Service 先行查重 + uk_name 唯一键并发兜底，与 2201 标签重名对称） */
    CATEGORY_NAME_DUPLICATE(2103, "分类名已存在"),
    TAG_NAME_DUPLICATE(2201, "标签名重复"),
    /** M4：标签 id 不存在/已删除（与 2003 分类不存在同语义的标签版） */
    TAG_NOT_FOUND(2202, "标签不存在"),
    COMMENT_NOT_ALLOWED(2301, "文章不允许评论"),
    COMMENT_PARENT_INVALID(2302, "父评论不存在或层级超限"),
    /** M5：评论 id 不存在/已删除（删除与审核入口共用；与 2202 标签不存在同族语义，已同步 API 文档 §1.4） */
    COMMENT_NOT_FOUND(2303, "评论不存在"),

    // ---------- 文件（3xxx） ----------
    FILE_TYPE_INVALID(3001, "文件类型不合法"),
    FILE_TOO_LARGE(3002, "文件超过 5MB 限制"),

    // ---------- AI（4xxx）★ 课题重点模块的降级语义 ----------
    /** AI 服务未配置（管理员未设默认配置）：前端按钮置灰 */
    AI_NOT_CONFIGURED(4001, "AI 服务未配置"),
    /** 调用失败/超时：降级返回，不抛 500，保证"AI 挂不影响发文" */
    AI_CALL_FAILED(4002, "AI 服务暂不可用，请稍后重试或手动填写"),
    /** 用户日配额超限（HTTP 429） */
    AI_QUOTA_EXCEEDED(4003, "今日 AI 调用次数已达上限"),
    /** 管理员全局关闭 AI 功能 */
    AI_DISABLED(4004, "AI 功能已被管理员关闭"),
    /** 模型输出无法解析为约定格式，建议重试 */
    AI_BAD_OUTPUT(4005, "AI 返回格式非法，建议重试"),
    /** 连接测试：Key 被上游拒绝（401） */
    AI_KEY_INVALID(4101, "API Key 无效"),
    /** 连接测试：Base URL 网络不可达 */
    AI_BASE_URL_UNREACHABLE(4102, "Base URL 不可达"),

    // ---------- 系统配置（5xxx） ----------
    CONFIG_NOT_FOUND(5001, "配置项不存在");

    /** 业务码：写入统一响应体 code 字段 */
    private final int code;
    /** 默认中文提示：可直接展示给用户（服务端已本地化，前端不再二次翻译） */
    private final String message;
}
