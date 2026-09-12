package com.blog.module.ai.support;

import com.blog.module.ai.client.LlmClient;
import com.blog.module.ai.client.LlmRequest;
import com.blog.module.ai.client.LlmResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试桩大模型客户端（M6）：确定性输出脚本，替代真实出网的 OpenAiCompatClient。
 *
 * <p>为什么桩在 LlmClient 接口层而不是更高层（如 mock RestTemplate）：接口语义
 * （chat 要么回 LlmResponse 要么抛 LlmCallException）就是客户端与门面的契约边界，
 * 桩在这里能精确模拟"上游 401/超时/乱输出"三类降级场景，且完全不碰网络
 * ——CI/断网维护窗口下测试矩阵照跑（项目计划 R-02 的可测性预案）。
 *
 * <p>脚本队列 FIFO：enqueue 一次出队一条；队列空时回退固定 "OK" 响应（连通测试够用）。
 * failWith 置位后所有调用抛指定异常（模拟持续故障/配额场景里的上游失败）。
 * lastRequest 供断言"提示词装配正确/Key 已解密注入头"等管道中间产物。
 */
public class StubLlmClient implements LlmClient {

    private final List<LlmResponse> script = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile RuntimeException failure;
    private volatile LlmRequest lastRequest;

    @Override
    public LlmResponse chat(LlmRequest request) {
        calls.incrementAndGet();
        lastRequest = request;
        if (failure != null) {
            throw failure;
        }
        synchronized (script) {
            if (!script.isEmpty()) {
                return script.remove(0);
            }
        }
        return LlmResponse.builder().content("OK").build();
    }

    /** 预置一条成功响应（出队顺序 = 入队顺序） */
    public void enqueue(LlmResponse resp) {
        synchronized (script) {
            script.add(resp);
        }
    }

    /** 让后续所有 chat 抛该异常（failWith(null) 恢复成功模式） */
    public void failWith(RuntimeException e) {
        this.failure = e;
    }

    public void reset() {
        synchronized (script) {
            script.clear();
        }
        failure = null;
        lastRequest = null;
        calls.set(0);
    }

    public int callCount() {
        return calls.get();
    }

    public LlmRequest lastRequest() {
        return lastRequest;
    }
}
