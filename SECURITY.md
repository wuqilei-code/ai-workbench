# 安全策略

## 报告漏洞

请**不要**通过公开 Issue 报告安全问题。请使用以下任一渠道私下披露:

- GitHub 仓库的 **Security → Report a vulnerability**(Security Advisories),这是首选方式;
- 或邮件联系维护者(见仓库主页)。

请在报告中尽量包含:影响范围、复现步骤、版本/提交、以及可能的缓解建议。
我们会在确认后尽快回复,并在修复发布后再公开细节。

## 支持范围

仅对默认分支(`deepagents`)的最新代码提供安全修复。

## 部署方须知(重要)

本项目是**面向内网私有化部署**的多租户平台,不是可直接暴露到公网的服务。
部署与使用时请务必:

- **不要**把 `agent-service`(`:8000`)或 per-user 沙箱容器(`aio-sandbox-*`)直接暴露到公网;
  `agent-service` 无自身鉴权,`user_id` 即身份。
- 为 `JWT_SECRET` / `APP_AES_KEY` / `INTERNAL_TOKEN` / 数据库密码注入**随机值**
  (`openssl rand -hex 32` 等),不要使用示例占位值。
- 生产环境将 `APP_REGISTRATION_ENABLED=false`,按需开启注册。
- 视需要配置 `INTERNAL_TRUSTED_CIDRS`(内部接口来源白名单)与
  `AGENT_OUTBOUND_ALLOWLIST`(出站 SSRF 白名单)。
- 沙箱以 `seccomp=unconfined` 运行且共享宿主卷,请确保运行宿主本身可信、仅限受控网络访问。
- 定期轮换所有密钥与模型 API Key;不要把密钥写入仓库或镜像。
