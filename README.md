# Coding Switch

*Languages: [English](README.md) | [中文](README_zh.md)*

## Overview

Coding Switch is an All-in-One configuration manager for AI Coding CLI tools, built as a plugin for JetBrains IDEs. It helps developers seamlessly manage and switch configurations, API keys, and context for various popular AI code generation tools.

## Features

- **Provider Management**: Effortlessly manage and switch API configurations for Claude Code, Codex (OpenAI), Gemini CLI, and OpenCode. Supports official APIs as well as custom base URLs for third-party or local providers, including popular Chinese LLM services (DeepSeek, Zhipu, Kimi, Baidu Qianfan, Alibaba).
- **MCP Management**: Unified Model Context Protocol (MCP) server management across all supported AI CLIs.
- **Skills Management**: Discover, install, and manage custom skills (e.g., Claude Skills) directly from GitHub repositories.
- **Prompts Management**: Manage multiple preset system prompts with Markdown support to easily switch the context and behavior of your AI assistants.

## Supported AI CLIs

| Tool | Official API | Third-party API |
|------|-------------|-----------------|
| Claude Code | ✅ | ✅ |
| Codex | ✅ | ✅ |
| Gemini CLI | ✅ | ❌ |
| OpenCode | N/A | ✅ |

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
