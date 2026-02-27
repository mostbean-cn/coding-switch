# Coding Switch

*语言: [English](README.md) | [中文](README_zh.md)*

## 简介

Coding Switch 是一款专为 JetBrains IDE 打造的 AI 编程 CLI 工具多合一配置管理器。它可以帮助开发者无缝管理、切换多种流行 AI 编程辅助工具的配置和上下文环境变量。

## 核心功能

- **供应商配置管理 (Provider)**：轻松管理和批量切换 Claude Code、Codex (OpenAI)、Gemini CLI 和 OpenCode 的 API 配置。支持官方接口以及自定义第三方或本地大模型接口，内置多个国产大模型预设（DeepSeek、智谱 GLM、MiniMax、Kimi、百度千帆、阿里 Plan 等）。
- **MCP 管理**：为所有支持的 AI CLI 提供统一的 Model Context Protocol (MCP) 服务器管理。
- **技能管理 (Skills)**：直接从 GitHub 仓库发现、安装和管理自定义技能（例如 Claude Skills）。
- **提示词管理 (Prompts)**：支持 Markdown 的多系统提示词管理，方便快速切换 AI 助手的上下文和预设行为模式。

## 支持的工具

| 工具 | 官方 API | 第三方 API |
|------|---------|-----------|
| Claude Code | ✅ | ✅ |
| Codex | ✅ | ✅ |
| Gemini CLI | ✅ | ❌ |
| OpenCode | 不适用 | ✅ |

## 内置预设供应商

### Claude Code
- DeepSeek
- 智谱 GLM
- MiniMax
- Kimi
- 百度千帆
- 阿里 Plan

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
