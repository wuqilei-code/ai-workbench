package com.aiworkbench.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * 懒播种平台内置技能:技能栏/技能接口在读取前先请 agent-service 为该用户拷贝内置技能
 * (seed 只拷 SKILL.md 到 deepagents 技能根,不起容器,幂等)。
 */
@Slf4j
@Component
public class AgentSkillSeeder {

    private final WebClient webClient;

    public AgentSkillSeeder(@Value("${deepagents.base-url:http://localhost:8000}") String agentBaseUrl) {
        String base = (agentBaseUrl == null || agentBaseUrl.isBlank()) ? "http://localhost:8000" : agentBaseUrl;
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.webClient = WebClient.builder().baseUrl(base).build();
    }

    public void seed(Long userId) {
        try {
            webClient.post()
                    .uri("/api/users/{userId}/skills/seed", userId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(10));
        } catch (Exception e) {
            log.debug("seed skills request failed (user={}): {}", userId, e.toString());
        }
    }
}