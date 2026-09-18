# Pi 配置说明

Pi 是 Coding Switch 的托管 CLI 之一。默认不出现在可见列表中，需要在设置页「CLI 配置」中手动启用。

## 配置文件

| 用途 | 路径 |
|------|------|
| 登录凭证 | `~/.pi/agent/auth.json` |
| 自定义模型 | `~/.pi/agent/models.json` |
| 全局设置 | `~/.pi/agent/settings.json` |
| 全局提示词 | `~/.pi/agent/AGENTS.md` |
| Skills | `~/.pi/agent/skills/` |
| 会话 | `~/.pi/agent/sessions/` |

## 官方登录

激活官方登录配置时，插件会备份并恢复 `~/.pi/agent/auth.json` 中的 OAuth 凭证，并移除插件托管的 `coding-switch` 自定义模型段。首次使用可在终端进入 Pi 后执行 `/login`。

## 内置目录

DeepSeek、智谱 GLM、Kimi、MiniMax、阿里 Plan、MiMo Plan 走 Pi 内置供应商目录，只写入：

- `auth.json` 对应 provider 的 `api_key`
- `settings.json` 的 `defaultProvider` / `defaultModel`

不会覆盖其他供应商的 OAuth 或 API Key。

## 自定义 API

自定义兼容接口额外写入 `models.json` 的 `providers.coding-switch` 段，并设置默认模型。

支持的 API 类型：

- `openai-completions`
- `openai-responses`
- `anthropic-messages`

## 安装与更新

- 安装：`npm install -g --ignore-scripts @earendil-works/pi-coding-agent`
- Linux / macOS 可选：`curl -fsSL https://pi.dev/install.sh | sh`
- 更新：`pi update`

## 说明

当前版本不同步 MCP。Pi 官方没有 Claude / Codex 那种 `mcpServers` 配置。
