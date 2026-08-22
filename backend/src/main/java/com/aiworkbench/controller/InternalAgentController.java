package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.dto.skill.UserSkillRequest;
import com.aiworkbench.dto.skill.UserSkillVo;
import com.aiworkbench.service.UserSkillService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * agent-service 内部回调接口(供 create_skill 工具回写技能库)。
 * 鉴权:X-Internal-Token 必须与 app.internal-token 一致;未配置令牌时一律拒绝。
 */
@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
public class InternalAgentController {

    private final UserSkillService userSkillService;

    @Value("${app.internal-token:}")
    private String internalToken;

    @PostMapping("/users/{userId}/skills/upsert")
    public R<UserSkillVo> upsertSkill(@PathVariable Long userId,
                                      @RequestHeader(value = "X-Internal-Token", required = false) String token,
                                      @RequestBody UserSkillRequest req) {
        if (internalToken == null || internalToken.isBlank()
                || token == null || !internalToken.equals(token)) {
            return R.fail(ResultCode.FORBIDDEN);
        }
        return R.ok(userSkillService.upsertFromAgent(userId, req));
    }
}
