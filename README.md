# Coding Switch

*Languages: [English](README.md) | [中文](README_zh.md)*

## Overview

Coding Switch is an AI Coding CLI and IDE productivity toolkit for JetBrains IDEs. It helps developers manage Claude Code, Codex, Gemini CLI, OpenCode, and other AI coding command-line tools in one place, while also providing inline AI code completion, Git commit message generation, path-with-line-number insertion, CLI quick launch, and CLI version checks.

## Features

- **AI CLI Provider Management**: Manage and switch API configurations for Claude Code, Codex (OpenAI), Gemini CLI, and OpenCode. Supports official APIs, third-party compatible endpoints, and local providers, with built-in presets for services such as DeepSeek, Zhipu GLM, MiniMax, Kimi, Baidu Qianfan, and Alibaba Tongyi. Includes connection tests and health checks before activation.
- **CLI Quick Launch and Version Checks**: View installation status, version information, and install/update commands inside the IDE. Launch terminal sessions with the selected CLI configuration without manually preparing environment variables or config files.
- **Inline AI Code Completion**: Provides ghost-text inline completion with automatic triggering, manual triggering, full Tab acceptance, and line-by-line acceptance. Completion models preferably use FIM Completions or FIM Chat Completions, while OpenAI Chat Completions, OpenAI Responses, and Anthropic Messages compatible models are also supported.
- **Git Commit Message Generation**: Generate Conventional Commits style messages from selected changes in the commit tool window. Git models can be configured separately from code completion models.
- **Editor Path and Line-Number Insertion**: Insert file paths from the editor context menu, or insert precise path-with-line-number references when a code range is selected.
- **MCP Management**: Unified Model Context Protocol (MCP) server management across all supported AI CLIs.
- **Skills Management**: Discover, install, update, and uninstall custom skills (e.g., Claude Skills) directly from GitHub repositories, with ZIP-download fallback for install/update when git is unavailable. Built-in discovery includes 4 recommended repositories (ComposioHQ/awesome-claude-skills, JimLiu/baoyu-skills, anthropics/skills, cexll/myclaude), with one-click install from the Skills panel `+` button and external browser open for repository skills pages. Besides Claude native skills, Codex/Gemini/OpenCode are also adapted via an auto-managed Skills Bridge in their prompt files.
- **Prompts Management**: Manage multiple preset system prompts with Markdown support to easily switch the context and behavior of your AI assistants.

## Supported AI CLIs

| Tool | Official API | Third-party API |
|------|-------------|-----------------|
| Claude Code | ✅ | ✅ |
| Codex | ✅ | ✅ |
| Gemini CLI | ✅ | ❌ |
| OpenCode | N/A | ✅ |

## Supported Code Completion Protocols

| Protocol | Best For |
|----------|----------|
| FIM Completions | Recommended for code completion models that support `prompt + suffix` FIM requests |
| FIM Chat Completions | FIM models exposed through Chat Completions with prefix/suffix in the request body |
| OpenAI Chat Completions | General chat models, optionally with FIM format adaptation |
| OpenAI Responses | OpenAI Responses compatible models |
| Anthropic Messages | Anthropic Messages compatible models |

## Built-in Provider Presets

### Claude Code

- DeepSeek
- Zhipu GLM
- MiniMax
- Kimi
- Baidu Qianfan
- Alibaba Tongyi

### Codex / OpenCode

- DeepSeek
- Zhipu GLM
- Kimi

## Installation

*Currently, you can build and run this plugin from source using Gradle:*

```bash
./gradlew runIde
```

## Privacy

This plugin only reads and writes local configuration files for the supported AI CLI tools. No user data or telemetry is collected.

## Disclaimer & Legal Notice

**⚠️ For Personal Study and Research Only**
This project is for personal learning and research purposes only. By using this project, you agree to:

- Not use this project for any commercial purposes.
- Assume all risks and responsibilities associated with the use of this project.
- Comply with all applicable Terms of Service and legal regulations.
- The project author assumes no liability for any direct or indirect loss or damage arising from the use of this project.

**⚠️ Unofficial Tool:**
This plugin is an unofficial, community-driven tool and is **NOT** affiliated with, endorsed by, or sponsored by Anthropic, OpenAI, Google, Cursor, JetBrains, or any other supported AI service providers. All trademarks, service marks, and company names are the property of their respective owners.

## Acknowledgements

This project references concepts and logic from the following open-source projects:

- **[cc-switch](https://github.com/farion1231/cc-switch)** - Referenced for the concepts and implementation details of Claude Code configuration switching.
