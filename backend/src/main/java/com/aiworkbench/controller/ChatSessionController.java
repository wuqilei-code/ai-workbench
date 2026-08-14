package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import com.aiworkbench.common.SecurityUtil;
import com.aiworkbench.common.audit.AuditLog;
import com.aiworkbench.dto.chat.AgentVo;
import com.aiworkbench.dto.chat.CreateSessionRequest;
import com.aiworkbench.dto.chat.SessionVo;
import com.aiworkbench.dto.chat.UpdateSessionRequest;
import com.aiworkbench.service.ChatSessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat/sessions")
@RequiredArgsConstructor
public class ChatSessionController {

    private final ChatSessionService chatSessionService;
    private final com.aiworkbench.service.opencode.OpencodeClient opencodeClient;

    @PostMapping
    @AuditLog(action = "创建会话")
    public R<SessionVo> create(@Valid @RequestBody CreateSessionRequest req) {
        return R.ok(chatSessionService.create(req, SecurityUtil.currentUserId()));
    }

    /** 导入会话:body 为导出的 JSON(含 sessionTitle + messages) */
    @PostMapping("/import")
    @AuditLog(action = "导入会话")
    public R<SessionVo> importSession(@RequestBody Map<String, String> body) {
        String json = body != null ? body.get("json") : null;
        if (json == null || json.isBlank()) {
            throw new com.aiworkbench.common.BusinessException(
                    com.aiworkbench.common.ResultCode.BAD_REQUEST.getCode(), "json 不能为空");
        }
        return R.ok(chatSessionService.importSession(SecurityUtil.currentUserId(), json));
    }

    /**
     * 预热:进入会话页时调用,提前拉起该用户 opencode 进程(幂等,阻塞至就绪),
     * 避免首条消息冷启动等待。不审计(高频无业务语义)。
     */
    /**
     * 可用 agent 列表(per-user,过滤 hidden 与 subagent)。
     * 注意:字面量 /agents 优先于 /{id} 匹配,无需会话上下文。
     */
    @GetMapping("/agents")
    public R<List<AgentVo>> agents() {
        Long userId = SecurityUtil.currentUserId();
        List<AgentVo> agents = opencodeClient.listAgents(userId).stream()
                .filter(a -> !Boolean.TRUE.equals(a.getHidden()))
                .filter(a -> !"subagent".equals(a.getMode()))
                .map(a -> AgentVo.builder()
                        .name(a.getName())
                        .description(a.getDescription())
                        .mode(a.getMode())
                        .build())
                .toList();
        return R.ok(agents);
    }

    @PostMapping("/{id}/warmup")
    public R<Void> warmup(@PathVariable Long id) {
        Long userId = SecurityUtil.currentUserId();
        chatSessionService.getOwned(id, userId);
        opencodeClient.warmup(userId);
        return R.ok();
    }

    /** 按当前用户预热 agent(无需指定会话)。登录/进入主页时调用,提前拉起该用户 opencode
     *  实例,避免点「新建会话」时才冷启动容器而等待数秒。高频,不审计。 */
    @PostMapping("/warmup")
    public R<Void> warmupMine() {
        opencodeClient.warmup(SecurityUtil.currentUserId());
        return R.ok();
    }

    @GetMapping
    public R<List<SessionVo>> list() {
        return R.ok(chatSessionService.list(SecurityUtil.currentUserId()));
    }

    /** 已归档会话列表 */
    @GetMapping("/archived")
    public R<List<SessionVo>> listArchived() {
        return R.ok(chatSessionService.listArchived(SecurityUtil.currentUserId()));
    }

    /** 会话搜索:标题或消息内容模糊匹配(含归档)。字面量 /search 优先于 /{id} 匹配。 */
    @GetMapping("/search")
    public R<List<SessionVo>> search(@RequestParam String q) {
        return R.ok(chatSessionService.search(q, SecurityUtil.currentUserId()));
    }

    @GetMapping("/{id}")
    public R<SessionVo> get(@PathVariable Long id) {
        return R.ok(chatSessionService.get(id, SecurityUtil.currentUserId()));
    }

    @PatchMapping("/{id}")
    public R<SessionVo> update(@PathVariable Long id, @RequestBody UpdateSessionRequest req) {
        return R.ok(chatSessionService.update(id, SecurityUtil.currentUserId(), req));
    }

    @DeleteMapping("/{id}")
    @AuditLog(action = "删除会话", targetSpel = "#id")
    public R<Void> delete(@PathVariable Long id) {
        chatSessionService.delete(id, SecurityUtil.currentUserId());
        return R.ok();
    }
}
