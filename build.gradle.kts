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

    // SQLite access (for cc-switch data sync)
    // 注意：使用裁剪后的版本，原始依赖在 sqliteOriginal 配置中
    // implementation(libs.sqlite)

    // Markdown rendering
    implementation(libs.commonmark)

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
            <p>Manage Claude Code, Codex, OpenCode, Antigravity CLI, Grok, Pi, and other AI coding command-line tools in JetBrains IDEs, with inline AI code completion, Git commit message generation, path-with-line-number insertion, CLI quick launch, and CLI version checks.</p>

            <h3>Features</h3>
            <ul>
                <li><b>AI CLI Provider Management</b> — Manage and switch API configurations for Claude Code, Codex, OpenCode, Antigravity CLI, Grok, and Pi with official APIs, third-party compatible endpoints, local providers, connection tests, and built-in presets.</li>
                <li><b>CLI Quick Launch and Version Checks</b> — View installation status and version information, then launch terminal sessions with the selected CLI configuration.</li>
                <li><b>Inline AI Code Completion</b> — Use ghost-text completion with automatic/manual triggering, Tab acceptance, line-by-line acceptance, and FIM Completions / FIM Chat Completions support.</li>
                <li><b>Code Path and Line Number Quick Insert</b> — One-click insert file paths or code snippet locations with line numbers from the editor and project tree context menus into CLI terminals.</li>
                <li><b>Git Commit Message Generation</b> — Generate Conventional Commits style messages from selected changes in the commit tool window.</li>
                <li><b>MCP, Skills, and Prompts</b> — Manage MCP servers, GitHub-based skills, and Markdown prompt presets across supported AI CLIs.</li>
            </ul>

            <h3>功能</h3>
            <ul>
                <li><b>AI CLI 配置管理</b> — 管理并切换 Claude Code、Codex、OpenCode、Antigravity CLI、Grok、Pi 的 API 配置，支持官方接口、第三方兼容接口、本地模型、连接测试和内置供应商预设。</li>
                <li><b>CLI 快速启动与版本检测</b> — 在 IDE 内查看安装状态和版本信息，并使用选中的 CLI 配置快速启动终端会话。</li>
                <li><b>行内 AI 代码补全</b> — 支持灰字补全、自动触发、手动触发、Tab 全量采纳、逐行采纳，以及 FIM Completions / FIM Chat Completions。</li>
                <li><b>代码路径与行号快速插入</b> — 从编辑器、项目树右键菜单一键插入文件路径或带行号的代码片段位置到 CLI 终端。</li>
                <li><b>Git 提交信息生成</b> — 基于选中变更生成 Conventional Commits 风格提交信息。</li>
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

/**
 * 裁剪 SQLite JDBC 原生库配置。
 *
 * sqlite-jdbc 打包了所有平台的原生库（14MB），但 JetBrains IDE 只需要桌面端的几个架构。
 * 此配置创建一个裁剪版本的依赖，删除用不到的原生库文件，预计减少 7-10MB。
 *
 * 保留架构：
 * - Windows/x86_64
 * - Mac/x86_64, Mac/aarch64 (Apple Silicon)
 * - Linux/x86_64, Linux/aarch64
 */
configurations {
    create("sqliteOriginal")
}

dependencies {
    // 原始 SQLite 依赖移到单独的配置中，不直接作为 implementation
    "sqliteOriginal"(libs.sqlite)
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    /**
     * 裁剪 SQLite JDBC 原生库任务。
     */
    val stripSqliteNativeLibs = register<Jar>("stripSqliteNativeLibs") {
        description = "裁剪 SQLite JDBC 中用不到的原生库以减小插件体积"
        group = "build"

        archiveBaseName.set("sqlite-jdbc")
        archiveVersion.set("3.49.1.0-stripped")
        destinationDirectory.set(layout.buildDirectory.dir("sqlite"))

        // 需要保留的平台路径（注意大小写：Windows/Mac/Linux）
        val keepPatterns = setOf(
            "Windows/x86_64",
            "Mac/x86_64",
            "Mac/aarch64",
            "Linux/x86_64",
            "Linux/aarch64"
        )

        // 在配置期获取配置，避免配置缓存问题
        val sqliteFiles = configurations.named("sqliteOriginal")
        from(sqliteFiles.map { config ->
            config.files.map { file ->
                zipTree(file).matching {
                    exclude { fileTreeElement ->
                        val path = fileTreeElement.relativePath.pathString
                        // 排除不需要的原生库
                        if (path.startsWith("org/sqlite/native/")) {
                            // 提取平台路径，例如 "org/sqlite/native/Linux/armv7/xxx.so" -> "Linux/armv7"
                            val nativeRelPath = path.substring("org/sqlite/native/".length)
                            val segments = nativeRelPath.split("/")
                            if (segments.size >= 2) {
                                val platformPath = "${segments[0]}/${segments[1]}"
                                val shouldKeep = keepPatterns.contains(platformPath)
                                !shouldKeep
                            } else {
                                false
                            }
                        } else {
                            false
                        }
                    }
                }
            }
        })

        doLast {
            val size = archiveFile.get().asFile.length()
            val originalSize = 14317659L // 原始 sqlite-jdbc-3.49.1.0.jar 大小
            val saved = originalSize - size
            logger.lifecycle("✓ SQLite JDBC 裁剪完成: ${size / 1024 / 1024}MB (节省 ${saved / 1024 / 1024}MB)")
        }
    }

    // 在编译前生成裁剪后的 SQLite
    named("compileJava") {
        dependsOn(stripSqliteNativeLibs)
    }
}

// 将裁剪后的 SQLite 作为实现依赖
dependencies {
    implementation(files(tasks.named<Jar>("stripSqliteNativeLibs").map { it.archiveFile }))
}
