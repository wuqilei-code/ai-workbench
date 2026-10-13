# 贡献指南

感谢参与 AI Workbench!本项目采用 **Apache-2.0** 许可,提交贡献即表示你同意以该许可发布你的贡献。

## 开发环境

- 后端:JDK 21 + Maven 3.6+(`cd backend && mvn clean package -DskipTests`)
- 前端:Node 18+ 推荐(`cd frontend && npm install && npm run dev`)
- 智能体:`cd agent-service && pip install -r requirements.txt`
- 全栈联调:`cd docker && cp .env.example .env`(填入随机密钥)后 `docker compose up --build`

## 提交前自检(CI 门禁)

请在提交 PR 前本地跑通以下检查,CI 会执行同样内容:

```bash
cd backend       && mvn test
cd frontend      && npm run typecheck && npm run test && npm run build
cd agent-service && pytest -q
```

## 分支与提交

- 从 `deepagents` 切出特性分支:`feat/xxx`、`fix/xxx`、`docs/xxx`、`chore/xxx`。
- 提交信息建议遵循 Conventional Commits(如 `feat(frontend): ...`)。
- 保持改动聚焦;一个 PR 解决一件事。

## Pull Request

- 说明**动机**与**改动**;关联相关 Issue(如 `Closes #123`)。
- UI 改动请附截图/录屏;行为改动请补测试。
- 确保不引入密钥、内部地址或个人凭据。
- 保持现有代码风格与目录约定,不引入未在项目中使用的依赖。

## 报告问题

- Bug / 功能请求请使用 Issue 模板。
- 安全漏洞请**不要**开公开 Issue,见 [`SECURITY.md`](SECURITY.md)。
