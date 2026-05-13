import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    alias(libs.plugins.intelliJPlatform)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // JSON processing
    implementation(libs.gson)

    // TOML processing (for Codex config.toml)
    implementation(libs.toml4j)

    // Test
    testImplementation(libs.junit)

    // IntelliJ Platform
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))

        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })
        bundledModules(providers.gradleProperty("platformBundledModules").map { it.split(',') })

        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        description = """
            <h2>Coding Switch - AI Coding CLI and IDE Productivity Toolkit</h2>
            <p>Manage Claude Code, Codex, Gemini CLI, OpenCode, and other AI coding command-line tools in JetBrains IDEs, with inline AI code completion, Git commit message generation, path-with-line-number insertion, CLI quick launch, and CLI version checks.</p>

            <h3>Features</h3>
            <ul>
                <li><b>AI CLI Provider Management</b> — Manage and switch API configurations for Claude Code, Codex, Gemini CLI, and OpenCode with official APIs, third-party compatible endpoints, local providers, connection tests, and built-in presets.</li>
                <li><b>CLI Quick Launch and Version Checks</b> — View installation status and version information, then launch terminal sessions with the selected CLI configuration.</li>
                <li><b>Inline AI Code Completion</b> — Use ghost-text completion with automatic/manual triggering, Tab acceptance, line-by-line acceptance, and FIM Completions / FIM Chat Completions support.</li>
                <li><b>Git and Editor Helpers</b> — Generate Conventional Commits style messages from selected changes and insert file paths or path-with-line-number references from the editor context menu.</li>
                <li><b>MCP, Skills, and Prompts</b> — Manage MCP servers, GitHub-based skills, and Markdown prompt presets across supported AI CLIs.</li>
            </ul>

            <h3>功能</h3>
            <ul>
                <li><b>AI CLI 配置管理</b> — 管理并切换 Claude Code、Codex、Gemini CLI、OpenCode 的 API 配置，支持官方接口、第三方兼容接口、本地模型、连接测试和内置供应商预设。</li>
                <li><b>CLI 快速启动与版本检测</b> — 在 IDE 内查看安装状态和版本信息，并使用选中的 CLI 配置快速启动终端会话。</li>
                <li><b>行内 AI 代码补全</b> — 支持灰字补全、自动触发、手动触发、Tab 全量采纳、逐行采纳，以及 FIM Completions / FIM Chat Completions。</li>
                <li><b>Git 与编辑器辅助</b> — 基于选中变更生成 Conventional Commits 风格提交信息，并从编辑器右键菜单插入文件路径或带行号的代码位置。</li>
                <li><b>MCP、技能与提示词管理</b> — 统一管理 MCP 服务器、GitHub 技能包和 Markdown 提示词预设，扩展多个 AI CLI 的上下文能力。</li>
            </ul>

            <h3>Privacy / 隐私</h3>
            <p>This plugin only reads and writes local configuration files. No user data is collected.</p>
            <p>本插件仅在本地读写配置文件，不会收集任何用户数据。</p>
        """.trimIndent()

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }
}
