# Coding Switch

*语言: [English](README.md) | [中文](README_zh.md)*

## 简介

Coding Switch 是一款专为 JetBrains IDE 打造的 AI 编程 CLI 与 IDE 辅助工具集。它可以在 IDE 内统一管理 Claude Code、Codex、Gemini CLI、OpenCode 等主流 AI 编程命令行工具，并提供行内 AI 代码补全、Git 提交信息生成、路径行号插入、CLI 快速启动与版本检测等日常开发能力。

## 核心功能

- **AI CLI 配置管理 (Provider)**：轻松管理和批量切换 Claude Code、Codex (OpenAI)、Gemini CLI 和 OpenCode 的 API 配置。支持官方接口以及自定义第三方或本地大模型接口，内置多个模型服务商预设（DeepSeek、智谱 GLM、MiniMax、Kimi、百度千帆、阿里通义等），并支持激活前连接测试与健康检查。
- **CLI 快速启动与版本检测**：在 IDE 内查看各 AI CLI 的安装状态、版本信息和安装/更新命令，并可使用当前选中的配置快速启动终端会话，减少命令行准备成本。
- **行内 AI 代码补全**：提供灰字行内补全体验，支持自动触发、手动触发、Tab 全量采纳和逐行采纳。补全模型优先支持 FIM Completions 与 FIM Chat Completions，也兼容 OpenAI Chat Completions、OpenAI Responses、Anthropic Messages 等协议。
- **Git 提交信息生成**：在提交工具栏中基于选中变更生成 Conventional Commits 风格提交信息，可单独配置 Git 模型，避免与代码补全模型混用。
- **编辑器路径与行号插入**：在编辑器右键菜单中快速插入文件路径；选中代码片段时可插入带行号的代码位置，方便给 AI CLI 提供精确上下文。
- **MCP 管理**：为所有支持的 AI CLI 提供统一的 Model Context Protocol (MCP) 服务器管理。
- **技能管理 (Skills)**：可从 GitHub 仓库发现、安装、更新和卸载自定义技能（例如 Claude Skills），形成完整闭环；无 Git 环境下支持 ZIP 下载解压安装/更新兜底。内置 4 个推荐仓库（ComposioHQ/awesome-claude-skills、JimLiu/baoyu-skills、anthropics/skills、cexll/myclaude），并支持在 Skills 面板 `+` 按钮中一键安装和外部浏览器打开仓库 Skills 地址。除 Claude 原生 Skills 外，Codex/Gemini/OpenCode 也可通过 Skills Bridge 自动同步到各自提示词文件。
- **提示词管理 (Prompts)**：支持 Markdown 的多系统提示词管理，方便快速切换 AI 助手的上下文和预设行为模式。

## 支持的工具

| 工具 | 官方 API | 第三方 API |
|------|---------|-----------|
| Claude Code | ✅ | ✅ |
| Codex | ✅ | ✅ |
| Gemini CLI | ✅ | ❌ |
| OpenCode | 不适用 | ✅ |

## 支持的代码补全协议

| 协议 | 适用场景 |
|------|---------|
| FIM Completions | 推荐用于代码补全，适合支持 `prompt + suffix` 的 FIM 专用模型 |
| FIM Chat Completions | 适合通过 Chat Completions 接口并在请求体中传入 prefix/suffix 的 FIM 模型 |
| OpenAI Chat Completions | 适合普通聊天模型，可配合 FIM 补全格式适配使用 |
| OpenAI Responses | 适合 OpenAI Responses 兼容模型 |
| Anthropic Messages | 适合 Anthropic Messages 兼容模型 |

## 内置预设供应商

### Claude Code
- DeepSeek
- 智谱 GLM
- MiniMax
- Kimi
- 百度千帆
- 阿里通义

### Codex / OpenCode
- DeepSeek
- 智谱 GLM
- Kimi

## 安装说明

*目前你可以通过 Gradle 从源码构建并运行该插件：*

```bash
./gradlew runIde
```

## 隐私说明

本插件仅在本地读写配置文件，不会收集任何用户数据或上传敏感信息。

## 免责声明与法律风险提示

**⚠️ 仅供个人学习和研究使用**
本项目仅供个人学习和研究使用。使用本项目即表示您同意：

- 不将本项目用于任何商业用途
- 承担使用本项目的所有风险和责任
- 遵守相关服务条款和法律法规
- 项目作者对因使用本项目而产生的任何直接或间接损失不承担责任。

**⚠️ 非官方工具声明：**
本插件是一个非官方的社区开源工具。**"Coding Switch"与 Anthropic、OpenAI、Google、Cursor、JetBrains 或任何其他受支持的 AI 服务提供商均无任何附属、赞助或官方合作关系。** 所有商标和品牌名称均属于其各自所有者。

## 鸣谢

本项目的灵感与部分核心逻辑实现，参考了优秀的开源社区项目：

- **[cc-switch](https://github.com/farion1231/cc-switch)** - 本项目中关于 Claude Code 多账号切换的思路及部分实现逻辑有参考该项目。
