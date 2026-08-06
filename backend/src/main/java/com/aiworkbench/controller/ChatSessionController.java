package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import com.aiworkbench.common.SecurityUtil;
import com.aiworkbench.common.audit.AuditLog;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/chat/sessions")
@RequiredArgsConstructor
public class ChatSessionController {

    private final ChatSessionService chatSessionService;

    @PostMapping
    @AuditLog(action = "创建会话")
    public R<SessionVo> create(@Valid @RequestBody CreateSessionRequest req) {
        return R.ok(chatSessionService.create(req, SecurityUtil.currentUserId()));
    }

    @GetMapping
    public R<List<SessionVo>> list() {
        return R.ok(chatSessionService.list(SecurityUtil.currentUserId()));
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
