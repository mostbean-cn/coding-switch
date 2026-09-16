# Grok 配置说明

Grok 是 Coding Switch 的托管 CLI 之一。默认不出现在可见列表中，需要在设置页「CLI 配置」中手动启用。

## 配置文件

| 用途 | 路径 |
|------|------|
| 官方登录凭证 | `~/.grok/auth.json` |
| 主配置 / 自定义模型 / MCP | `~/.grok/config.toml` |
| 全局提示词 | `~/.grok/rules/AGENTS.md` |
| Skills | `~/.grok/skills/` |
| 会话 | `~/.grok/sessions/` |

## 官方登录

激活官方登录配置时，插件会备份并恢复 `~/.grok/auth.json`，并移除插件托管的 `[model.*]` 段。首次使用可在终端执行 `grok login`。

## 自定义 API

自定义配置按 Grok 官方格式写入 `config.toml`：

```toml
[models]
default = "custom"

[model.custom]
model = "deepseek-v4-pro"
name = "DeepSeek"
base_url = "https://api.deepseek.com/v1"
api_key = "..."
api_backend = "chat_completions"
```

`api_backend` 支持：

- `chat_completions`（默认，OpenAI Chat Completions）
- `responses`（OpenAI Responses）
- `messages`（Anthropic Messages）

自定义模型的 `api_key` 优先于官方 session，因此切换到第三方配置时不会删除 `auth.json`。

## 安装与更新

- 安装（Windows）：`irm https://x.ai/cli/install.ps1 | iex`
- 安装（macOS/Linux）：`curl -fsSL https://x.ai/cli/install.sh | bash`
- 更新：`grok update`
