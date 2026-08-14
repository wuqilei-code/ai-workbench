package com.aiworkbench.service.opencode;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.service.WorkspaceService;
import com.aiworkbench.service.opencode.dto.CreateSessionBody;
import com.aiworkbench.service.opencode.dto.OpenCodeAgentDto;
import com.aiworkbench.service.opencode.dto.OpenCodeCommandDto;
import com.aiworkbench.service.opencode.dto.OpencodeMessageDto;
import com.aiworkbench.service.opencode.dto.OpencodeSessionDto;
import com.aiworkbench.service.opencode.dto.PromptAsyncBody;
import com.aiworkbench.service.opencode.process.OpencodeProcessManager;
import com.aiworkbench.service.opencode.process.UserProcess;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * opencode HTTP 客户端(Phase 2:per-user 多实例路由)。
 * <p>
 * 不再绑定单一 baseUrl:每个调用按 {@code userId} 经 {@link OpencodeProcessManager#getOrStart}
 * 取得该用户的 {@link UserProcess}(阻塞至 READY),用其实例 WebClient(已带 baseUrl + Basic auth)
 * 发请求,header {@code x-opencode-directory} 仍指向用户工作目录。
 * <p>
 * 同步方法用 .block()(servlet 栈);SSE 事件流由各实例的
 * {@link OpencodeInstanceEventConsumer} 订阅,不在本类。
 * <p>
 * 与 ProcessManager 存在循环依赖(Manager → ChatMessageService → 本类 → Manager),
 * 通过 {@code @Lazy} 注入 ProcessManager 打破。
 */
@Slf4j
@Service
public class OpencodeClient {

    private final OpencodeProcessManager processManager;
    private final WorkspaceService workspaceService;

    public OpencodeClient(@Lazy OpencodeProcessManager processManager, WorkspaceService workspaceService) {
        this.processManager = processManager;
        this.workspaceService = workspaceService;
    }

    /** POST /session — 建会话,目录隔离通过 header。按需首启该用户的 opencode 进程。 */
    public OpencodeSessionDto createSession(long userId, CreateSessionBody body) {
        return createSessionAt(userId, workspaceService.userDir(userId), body);
    }

    /**
     * POST /session — 建会话并显式指定工作目录(独立于用户工作区,用于变更总结等 scratch 会话,
     * 避免在用户真实工作区里被 agent 产生副作用)。按需首启该用户的 opencode 进程。
     */
    public OpencodeSessionDto createSessionAt(long userId, String directory, CreateSessionBody body) {
        UserProcess proc = processManager.getOrStart(userId);
        return proc.getWebClient().post().uri("/session")
                .header("x-opencode-directory", directory)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve().bodyToMono(OpencodeSessionDto.class)
                .block();
    }

    /**
     * 预热:按需启动该用户的 opencode 进程(阻塞至 READY),不产生会话/消息。
     * <p>
     * 用于用户进入会话页时提前拉起进程,避免首条消息冷启动阻塞数秒(进程由空闲回收管理,15min 无活动自动释放)。
     */
    public void warmup(long userId) {
        processManager.getOrStart(userId);
    }

    /** 该用户 agent 实例当前是否已就绪(不启动)。供冷启动时前端「agent 启动中」占位判定。 */
    public boolean isReady(long userId) {
        return processManager.isReady(userId);
    }

    /** POST /session/{id}/prompt_async — 异步发消息,流式走 /event(由实例消费者投递)。 */
    public void promptAsync(long userId, String opencodeSessionId, PromptAsyncBody body) {
        promptAsyncAt(userId, opencodeSessionId, workspaceService.userDir(userId), body);
    }

    /** POST /session/{id}/prompt_async — 显式指定目录(scratch 变更总结会话用,避免操作真实工作区)。 */
    public void promptAsyncAt(long userId, String opencodeSessionId, String directory, PromptAsyncBody body) {
        UserProcess proc = processManager.getOrStart(userId);
        proc.getWebClient().post().uri("/session/{id}/prompt_async", opencodeSessionId)
                .header("x-opencode-directory", directory)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve().bodyToMono(Void.class)
                .block();
    }

    /** POST /session/{id}/abort — 终止当前生成。无活跃进程(已空闲回收)则返回 false,不触发首启。 */
    public boolean abort(long userId, String opencodeSessionId) {
        UserProcess proc = processManager.getIfPresent(userId);
        if (proc == null) {
            return false;
        }
        String dir = workspaceService.userDir(userId);
        try {
            Boolean ok = proc.getWebClient().post().uri("/session/{id}/abort", opencodeSessionId)
                    .header("x-opencode-directory", dir)
                    .retrieve().bodyToMono(Boolean.class)
                    .block();
            return Boolean.TRUE.equals(ok);
        } catch (WebClientResponseException e) {
            log.warn("abort opencode session failed: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            return false;
        } catch (Exception e) {
            log.warn("abort opencode session error: {}", e.toString());
            throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE);
        }
    }

    /** POST /session/{id}/summarize — 主动触发会话压缩(用 compaction 模型总结早期内容)。 */
    public boolean summarize(long userId, String opencodeSessionId, String providerId, String modelId, boolean auto) {
        UserProcess proc = processManager.getOrStart(userId);
        String dir = workspaceService.userDir(userId);
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            if (providerId != null) body.put("providerID", providerId);
            if (modelId != null) body.put("modelID", modelId);
            body.put("auto", auto);
            proc.getWebClient().post().uri("/session/{id}/summarize", opencodeSessionId)
                    .header("x-opencode-directory", dir)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve().bodyToMono(Boolean.class)
                    .block();
            return true;
        } catch (WebClientResponseException e) {
            log.warn("summarize opencode session failed: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            return false;
        } catch (Exception e) {
            log.warn("summarize opencode session error: {}", e.toString());
            throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE);
        }
    }

    /** GET /session/{id} */
    public OpencodeSessionDto getSession(long userId, String opencodeSessionId) {
        UserProcess proc = processManager.getOrStart(userId);
        String dir = workspaceService.userDir(userId);
        return proc.getWebClient().get().uri("/session/{id}", opencodeSessionId)
                .header("x-opencode-directory", dir)
                .retrieve().bodyToMono(OpencodeSessionDto.class)
                .block();
    }

    /** DELETE /session/{id} */
    public void deleteSession(long userId, String opencodeSessionId) {
        UserProcess proc = processManager.getIfPresent(userId);
        if (proc == null) {
            return; // 进程已回收,opencode 侧会话随进程退出而消失,无需调用
        }
        String dir = workspaceService.userDir(userId);
        try {
            proc.getWebClient().delete().uri("/session/{id}", opencodeSessionId)
                    .header("x-opencode-directory", dir)
                    .retrieve().bodyToMono(Void.class)
                    .block();
        } catch (Exception e) {
            log.warn("delete opencode session failed, ignore: {}", e.toString());
        }
    }

    /** GET /session/{id}/message?limit=N — 消息历史(opencode 侧) */
    public List<OpencodeMessageDto> listMessages(long userId, String opencodeSessionId, int limit) {
        return listMessagesAt(userId, opencodeSessionId, workspaceService.userDir(userId), limit);
    }

    /** GET /session/{id}/message?limit=N — 显式指定目录(scratch 会话按该目录隔离查询)。 */
    public List<OpencodeMessageDto> listMessagesAt(long userId, String opencodeSessionId, String directory, int limit) {
        UserProcess proc = processManager.getOrStart(userId);
        return proc.getWebClient().get()
                .uri(b -> b.path("/session/{id}/message").queryParam("limit", limit).build(opencodeSessionId))
                .header("x-opencode-directory", directory)
                .retrieve().bodyToMono(new ParameterizedTypeReference<List<OpencodeMessageDto>>() {})
                .block();
    }

    /**
     * POST /question/{requestID}/reply — 回复问题。
     * <p>
     * opencode 的 question 工具(question.asked 事件)把待答问题存入<b>全局问题存储</b>(非 per-session 存储)。
     * 经抓包验证:per-session 路由 {@code /api/session/{sid}/question/{rid}/reply} 查询的是空的 per-session 存储,
     * 一律返回 404 QuestionNotFoundError;而全局路由 {@code /question/{rid}/reply} 才能命中并驱动 AI 继续生成。
     * 拒绝同理走 {@code /question/{rid}/reject}。x-opencode-directory 头仍需携带(全局存储按目录隔离)。
     */
    public void replyQuestion(long userId, String opencodeSessionId, String requestId, List<List<String>> answers) {
        try {
            UserProcess proc = processManager.getOrStart(userId);
            String dir = workspaceService.userDir(userId);
            proc.getWebClient().post()
                    .uri("/question/{requestId}/reply", requestId)
                    .header("x-opencode-directory", dir)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("answers", answers))
                    .retrieve().bodyToMono(Void.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.warn("reply question failed: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE);
        } catch (Exception e) {
            log.warn("reply question error: {}", e.toString());
            throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE);
        }
    }

    /** POST /question/{requestID}/reject — 拒绝问题(全局问题存储,见 {@link #replyQuestion} 说明)。 */
    public void rejectQuestion(long userId, String opencodeSessionId, String requestId) {
        try {
            UserProcess proc = processManager.getOrStart(userId);
            String dir = workspaceService.userDir(userId);
            proc.getWebClient().post()
                    .uri("/question/{requestId}/reject", requestId)
                    .header("x-opencode-directory", dir)
                    .retrieve().bodyToMono(Void.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.warn("reject question failed: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE);
        } catch (Exception e) {
            log.warn("reject question error: {}", e.toString());
            throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE);
        }
    }

    /**
     * POST /permission/{requestID}/reply — 回复权限。
     * <p>
     * 与 question 同理:permission 工具(permission.asked 事件)使用<b>全局权限存储</b>,
     * 需走全局路由 {@code /permission/{rid}/reply} 而非 per-session 的
     * {@code /api/session/{sid}/permission/{rid}/reply}(后者查 per-session 存储,会 404)。
     */
    public void replyPermission(long userId, String opencodeSessionId, String permissionId, String reply) {
        try {
            UserProcess proc = processManager.getOrStart(userId);
            String dir = workspaceService.userDir(userId);
            proc.getWebClient().post()
                    .uri("/permission/{requestId}/reply", permissionId)
                    .header("x-opencode-directory", dir)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("reply", reply))
                    .retrieve().bodyToMono(Void.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.warn("reply permission failed: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE);
        } catch (Exception e) {
            log.warn("reply permission error: {}", e.toString());
            throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE);
        }
    }

    /** GET /command — 该用户工作区的命令列表(内置 + 用户 command/*.md + MCP/skill),按目录隔离。 */
    public List<OpenCodeCommandDto> listCommands(long userId) {
        UserProcess proc = processManager.getOrStart(userId);
        String dir = workspaceService.userDir(userId);
        return proc.getWebClient().get().uri("/command")
                .header("x-opencode-directory", dir)
                .retrieve().bodyToMono(new ParameterizedTypeReference<List<OpenCodeCommandDto>>() {})
                .block();
    }

    /**
     * POST /session/{id}/command — 触发 slash 命令(fire-and-forget,不阻塞 servlet 线程)。
     * <p>
     * 该端点是<b>同步</b>的(会跑完整轮次后返回 {info, parts}),但本应用渲染完全依赖 SSE 事件
     * (OpencodeInstanceEventConsumer 镜像 user/assistant 消息),故像 prompt_async 一样仅发起即返回:
     * 模板展开、@file/!shell、agent/model 选择均在 opencode 侧完成,命令不存在等失败会发布
     * session.error 事件由前端展示,响应体无需等待。
     */
    public void runCommand(long userId, String opencodeSessionId, String command, String arguments) {
        UserProcess proc = processManager.getOrStart(userId);
        String dir = workspaceService.userDir(userId);
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("command", command);
        body.put("arguments", arguments == null ? "" : arguments);
        proc.getWebClient().post()
                .uri("/session/{id}/command", opencodeSessionId)
                .header("x-opencode-directory", dir)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve().bodyToMono(String.class)
                .subscribe(
                        v -> log.info("command {} executed (session {})", command, opencodeSessionId),
                        e -> log.warn("run command {} failed (session {}): {}",
                                command, opencodeSessionId,
                                e instanceof WebClientResponseException w
                                        ? w.getStatusCode() + " " + w.getResponseBodyAsString()
                                        : e.toString()));
    }

    /** GET /agent — 该用户工作区可用的 agent 列表(内置 + agents/*.md),按目录隔离。 */
    public List<OpenCodeAgentDto> listAgents(long userId) {
        UserProcess proc = processManager.getOrStart(userId);
        String dir = workspaceService.userDir(userId);
        return proc.getWebClient().get().uri("/agent")
                .header("x-opencode-directory", dir)
                .retrieve().bodyToMono(new ParameterizedTypeReference<List<OpenCodeAgentDto>>() {})
                .block();
    }

    /**
     * POST /api/session/{id}/agent — 切换会话 agent。
     * <p>
     * 注意:该路由只在 v2 前缀(/api/session/...)下存在,非 /api 的 /session/{id}/agent 不存在。
     * body: {agent: "name"},成功返回 204。
     */
    public void switchAgent(long userId, String opencodeSessionId, String agent) {
        try {
            UserProcess proc = processManager.getOrStart(userId);
            String dir = workspaceService.userDir(userId);
            proc.getWebClient().post()
                    .uri("/api/session/{id}/agent", opencodeSessionId)
                    .header("x-opencode-directory", dir)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("agent", agent))
                    .retrieve().bodyToMono(Void.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.warn("switch agent failed: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE);
        } catch (Exception e) {
            log.warn("switch agent error: {}", e.toString());
            throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE);
        }
    }
}
