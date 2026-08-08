package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import com.aiworkbench.common.SecurityUtil;
import com.aiworkbench.dto.chat.SkillInfoVo;
import com.aiworkbench.service.SkillService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/chat/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;

    /** 内置全局技能只读列表(随镜像内置,不可在此编辑)。 */
    @GetMapping("/builtin")
    public R<List<SkillInfoVo>> builtin() {
        return R.ok(skillService.builtinSkills(SecurityUtil.currentUserId()));
    }
}
