{
  "$schema": "https://opencode.ai/config.json",
  "autoCompact": 40000,
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
    "external_directory": "ask"
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
