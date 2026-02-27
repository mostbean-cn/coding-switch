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
            <h2>Coding Switch - AI Coding Assistant Config Manager</h2>
            <p>Manage and switch configurations for Claude Code, Codex, Gemini CLI, and OpenCode.</p>

            <h3>Features</h3>
            <ul>
                <li><b>Provider Management</b> — Manage API configurations with built-in presets for popular LLM services including DeepSeek, Zhipu, Kimi, Baidu Qianfan, Alibaba, and more</li>
                <li><b>MCP Management</b> — Unified MCP server management across all supported AI CLIs</li>
                <li><b>Skills Management</b> — Install and manage Claude Skills from GitHub</li>
                <li><b>Prompts Management</b> — Multi-preset system prompts with Markdown support</li>
            </ul>

            <h3>Privacy</h3>
            <p>This plugin only reads and writes local configuration files. No user data is collected.</p>
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
