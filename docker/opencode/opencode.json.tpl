{
  "$schema": "https://opencode.ai/config.json",
  "permission": {
    "bash": {
      "*": "allow",
      "rm *": "ask",
      "rmdir *": "ask",
      "mv *": "ask",
      "chmod *": "ask",
      "chown *": "ask",
      "curl *": "ask",
      "wget *": "ask",
      "git push *": "ask",
      "git reset --hard*": "ask",
      "git checkout *": "ask",
      "git clean *": "ask",
      "sudo *": "deny",
      "su *": "deny",
      "apt *": "deny",
      "apt-get *": "deny",
      "pip *": "deny",
      "pip3 *": "deny",
      "python* -m pip*": "deny",
      "python* -m ensurepip*": "deny"
    },
    "external_directory": {
      "*": "ask",
      "/tmp/opencode/**": "allow"
    }
  },
  "experimental": {
    "continue_loop_on_deny": true
  },
  "compaction": {
    "auto": true,
    "prune": false,
    "reserved": 10000
  },
  "agent": {
    "build": {
      "prompt": "When the user rejects or skips a question you asked (they chose 拒绝/跳过), do NOT ask the same or a similar question again. Proceed with the best available information, state your assumptions, and continue the task. Only ask a follow-up question if a genuinely new, blocking decision is required."
    }
  },
  "mcp": {
    "mysql": {
      "type": "local",
      "command": ["mcp-server-mysql"],
      "enabled": true,
      "environment": {
        "MYSQL_HOST": "192.168.0.161",
        "MYSQL_PORT": "3306",
        "MYSQL_USER": "aiworkbench",
        "MYSQL_PASS": "aiworkbench-pass",
        "MYSQL_DB": "ai_workbench"
      }
    }
  },
  "command": {
    "test": {
      "description": "源码测试分析:对工作区项目生成测试点/用例/脚本/报告",
      "template": "请对工作区里的项目源码做测试分析(用 test-analysis 技能):①生成测试点清单 ②生成测试用例 ③生成测试脚本(通过语法校验) ④输出测试报告。沙箱无法安装依赖,不要运行项目,只做静态分析与语法校验。$ARGUMENTS"
    },
    "summarize": {
      "description": "总结当前会话要点",
      "template": "请总结当前会话的要点:①本次讨论的核心内容 ②已确定的结论/决策 ③遗留的待办事项。条理清晰,便于回顾。"
    },
    "review": {
      "description": "代码审查:检查工作区指定文件或改动",
      "template": "请审查代码$ARGUMENTS:①代码质量与可读性 ②潜在 bug 与边界情况 ③安全问题 ④改进建议。"
    },
    "analyze-data": {
      "description": "数据分析:上传数据文件后分析",
      "template": "请分析数据文件$ARGUMENTS:①数据结构概览 ②关键指标 ③趋势与结论 ④值得关注的风险。"
    }
  },
  "provider": {
    "openai": {
      "id": "openai",
      "name": "OpenAI",
      "env": ["OPENAI_API_KEY"],
      "models": {
        "gpt-4o-mini": { "id": "gpt-4o-mini", "name": "GPT-4o mini" },
        "gpt-4o": { "id": "gpt-4o", "name": "GPT-4o" }
      }
    },
    "ollama": {
      "id": "ollama",
      "name": "Ollama (local)",
      "url": "http://127.0.0.1:11434/v1",
      "env": [],
      "models": {
        "llama3.1:8b": { "id": "llama3.1:8b", "name": "Llama 3.1 8B" }
      }
    },
    "deepseek": {
      "id": "deepseek",
      "name": "DeepSeek",
      "env": ["DEEPSEEK_API_KEY"],
      "models": {
        "deepseek-chat": { "id": "deepseek-chat", "name": "DeepSeek Chat" }
      }
    }
  }
}
