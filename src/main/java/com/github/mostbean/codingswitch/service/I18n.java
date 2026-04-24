package com.github.mostbean.codingswitch.service;

import com.intellij.DynamicBundle;
import java.text.MessageFormat;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 简单国际化工具：根据插件设置语言返回文案。
 */
public final class I18n {

        private static final Map<PluginSettings.Language, Map<String, String>> MESSAGES = createMessages();

        private I18n() {
        }

        public static PluginSettings.Language currentLanguage() {
                try {
                        return resolveEffectiveLanguage(PluginSettings.getInstance().getLanguage());
                } catch (Exception ignored) {
                        return PluginSettings.Language.EN;
                }
        }

        private static PluginSettings.Language resolveEffectiveLanguage(PluginSettings.Language configuredLanguage) {
                if (configuredLanguage == PluginSettings.Language.ZH || configuredLanguage == PluginSettings.Language.EN) {
                        return configuredLanguage;
                }
                return resolveIdeLanguage();
        }

        private static PluginSettings.Language resolveIdeLanguage() {
                try {
                        Locale locale = DynamicBundle.getLocale();
                        if (locale != null && Locale.CHINESE.getLanguage().equalsIgnoreCase(locale.getLanguage())) {
                                return PluginSettings.Language.ZH;
                        }
                } catch (Exception ignored) {
                        // 未能读取 IDE UI Locale 时，按非中英文 IDE 处理为英文。
                }
                return PluginSettings.Language.EN;
        }

        public static String t(String key, Object... args) {
                String template = resolveMessage(key, currentLanguage());
                return args == null || args.length == 0 ? template : MessageFormat.format(template, args);
        }

        private static String resolveMessage(String key, PluginSettings.Language language) {
                Map<String, String> selected = MESSAGES.get(language);
                if (selected != null && selected.containsKey(key)) {
                        return selected.get(key);
                }
                Map<String, String> zh = MESSAGES.get(PluginSettings.Language.ZH);
                if (zh != null && zh.containsKey(key)) {
                        return zh.get(key);
                }
                return key;
        }

        private static Map<PluginSettings.Language, Map<String, String>> createMessages() {
                Map<PluginSettings.Language, Map<String, String>> result = new EnumMap<>(PluginSettings.Language.class);
                result.put(PluginSettings.Language.ZH, createZhMessages());
                result.put(PluginSettings.Language.EN, createEnMessages());
                return result;
        }

        private static Map<String, String> createZhMessages() {
                Map<String, String> m = new HashMap<>();

                // ── ToolWindow 标签页 ──
                m.put("toolwindow.tab.providers", "配置管理");
                m.put("toolwindow.tab.sessions", "会话管理");
                m.put("toolwindow.tab.mcp", "MCP 服务");
                m.put("toolwindow.tab.skills", "Skills");
                m.put("toolwindow.tab.prompts", "提示词");
                m.put("toolwindow.tab.settings", "设置");
                m.put("common.button.ok", "确定");
                m.put("common.button.cancel", "取消");

                // ── Settings 面板 ──
                m.put("settings.section.versionStatus", "CLI 版本状态");
                m.put("settings.section.installCommands", "安装/更新命令");
                m.put("settings.section.storageLocation", "数据管理");
                m.put("settings.section.preferences", "偏好设置");
                m.put("settings.table.cli", "CLI");
                m.put("settings.table.currentVersion", "当前版本");
                m.put("settings.table.latestVersion", "最新版本");
                m.put("settings.status.checking", "检测中...");
                m.put("settings.status.notInstalled", "未安装");
                m.put("settings.status.detectTimeout", "检测超时");
                m.put("settings.status.detectFailed", "检测失败");
                m.put("settings.status.latest", "v{0} ✓ 已是最新");
                m.put("settings.status.updatable", "v{0} ⬆ 可更新");
                m.put("settings.status.localNewer", "v{0} / 当前版本较新");
                m.put("settings.button.checkAllVersions", "检测全部版本");
                m.put("settings.tooltip.copyClipboard", "复制到剪贴板");
                m.put("settings.label.uiLanguage", "界面语言:");
                m.put("settings.label.githubToken", "GitHub 令牌:");
                m.put("settings.label.dataStorageMode", "存储位置:");
                m.put("settings.label.featureSelection", "功能选择:");
                m.put("settings.label.cliSelection", "CLI配置:");
                m.put("settings.button.show", "显示");
                m.put("settings.button.hide", "隐藏");
                m.put("settings.button.configure", "功能配置");
                m.put("settings.button.cliConfig", "CLI配置");
                m.put("settings.button.restoreDefault", "恢复默认");
                m.put("settings.button.saveGithubToken", "保存令牌");
                m.put("settings.button.applyStorageMode", "应用");
                m.put("settings.button.openStorageDirectory", "打开目录");
                m.put("settings.hint.githubToken", "可选。配置 GitHub 令牌可提升 API 速率限制（仅影响 GitHub Skills 仓库）。");
                m.put("settings.hint.dataStorageMode", "Coding Switch 默认将配置数据保存在当前 IDE 的本地目录中。如需在 JetBrains 全家桶之间共享配置，请切换到“用户级共享”。");
                m.put("settings.hint.featureSelection", "用于控制顶部标签显示。");
                m.put("settings.hint.cliSelection", "用于控制本页“版本检测”和“安装/更新命令”中显示哪些 CLI。未选中时默认显示全部 CLI。");
                m.put("settings.githubToken.title", "设置");
                m.put("settings.githubToken.saved", "GitHub 令牌已保存");
                m.put("settings.hint.restartRequired", "⚠️ 切换语言后需要重启 IDE 才能完全生效");
                m.put("settings.dialog.languageChanged.message", "语言已切换为 {0}\n\n需要重启 IDE 才能完全生效。\n是否立即重启 IDE？");
                m.put("settings.dialog.languageChanged.title", "语言设置已更改");
                m.put("settings.dialog.languageChanged.restartNow", "立即重启");
                m.put("settings.dialog.languageChanged.restartLater", "稍后手动重启");
                m.put("settings.dialog.storageMode.title", "存储位置已更改");
                m.put("settings.dialog.storageMode.detectTitle", "检测到已有用户级配置");
                m.put("settings.dialog.storageMode.confirmTitle", "确认覆盖方式");
                m.put("settings.dialog.storageMode.appliedTitle", "存储位置已切换");
                m.put("settings.dialog.storageMode.confirm", "切换到 {0} 后，将自动迁移当前 Coding Switch 数据。\n\n是否继续？");
                m.put("settings.dialog.storageMode.confirmBackToLocal", "切换到 {0} 后，当前 IDE 会恢复使用自己的本地数据副本。\n\n是否继续？");
                m.put("settings.dialog.storageMode.confirmProceed", "继续执行");
                m.put("settings.dialog.storageMode.confirmYes", "继续切换");
                m.put("settings.dialog.storageMode.confirmNo", "取消");
                m.put("settings.dialog.storageMode.option.localToShared", "本地覆盖用户级");
                m.put("settings.dialog.storageMode.option.sharedToLocal", "用户级覆盖本地");
                m.put("settings.dialog.storageMode.conflict",
                                "检测到已有用户级配置。\n\n本地总计: {0} 条\n本地明细: Provider {2} / Prompt {3} / Skill {4} / MCP {5}\n\n用户级总计: {1} 条\n用户级明细: Provider {6} / Prompt {7} / Skill {8} / MCP {9}\n\n请选择要保留的数据来源：");
                m.put("settings.dialog.storageMode.confirmLocalToShared",
                                "你将使用当前 IDE 的本地数据覆盖用户级数据。\n\n覆盖后写入用户级的总计: {0} 条\n明细: Provider {1} / Prompt {2} / Skill {3} / MCP {4}\n\n当前 IDE 随后会切换到“用户级共享”。\n是否确认继续？");
                m.put("settings.dialog.storageMode.confirmSharedToLocal",
                                "你将使用现有用户级数据覆盖当前 IDE 的本地数据。\n\n将写回当前 IDE 本地的总计: {0} 条\n明细: Provider {1} / Prompt {2} / Skill {3} / MCP {4}\n\n当前 IDE 随后会切换到“用户级共享”。\n是否确认继续？");
                m.put("settings.dialog.storageMode.failed", "数据存储切换失败，请稍后重试。");
                m.put("settings.dialog.storageMode.applied", "已切换到 {0}，配置数据已立即生效。");
                m.put("settings.dialog.storageDirectory.openFailed", "打开数据目录失败: {0}");
                m.put("settings.dialog.featureSelection.title", "功能选择");
                m.put("settings.dialog.featureSelection.enabled", "启用的功能");
                m.put("settings.dialog.featureSelection.hidden", "隐藏的功能");
                m.put("settings.dialog.featureSelection.settingsPinned", "“设置”固定显示，不支持隐藏。 ");
                m.put("settings.dialog.cliSelection.title", "CLI配置");
                m.put("settings.dialog.cliSelection.enabled", "显示的 CLI");
                m.put("settings.dialog.cliSelection.hidden", "隐藏的 CLI");
                m.put("settings.dialog.cliSelection.managedHint", "仅 * 标记的 CLI 支持配置管理相关功能");

                // ── Session 面板 ──
                m.put("session.empty.selectHint", "选择一个会话查看详情");
                m.put("session.loading.messages", "加载消息中...");
                m.put("session.loading.scanning", "正在扫描会话...");
                m.put("session.empty.noMessages", "暂无消息记录");
                m.put("session.empty.noSessions",
                                "<html><center>未发现任何会话<br><br><font size='2' color='gray'>请确保已安装并使用过 Claude Code、Codex、<br>Gemini CLI 或 OpenCode 中的至少一个工具。</font></center></html>");
                m.put("session.button.continueConversation", "继续对话");
                m.put("session.button.delete", "删除会话");
                m.put("session.terminal.continueTabName", "继续对话");
                m.put("session.tooltip.refresh", "刷新会话列表");
                m.put("session.tooltip.copy", "复制: {0}");
                m.put("session.tooltip.delete", "删除当前会话");
                m.put("session.tooltip.deleteUnsupported", "当前 CLI 暂不支持删除会话");
                m.put("session.dialog.deleteTitle", "确认删除");
                m.put("session.dialog.deleteConfirm", "确定删除会话 \"{0}\" 吗？此操作不可恢复。");
                m.put("session.dialog.deleteUnsupported", "当前 CLI 暂不支持删除会话。");
                m.put("session.dialog.deleteFailed", "删除会话失败: {0}");
                m.put("session.dialog.continueFailed", "继续对话失败: {0}");
                m.put("session.content.truncated", "\n... (内容过长已截断)");
                m.put("session.time.unknown", "未知");
                m.put("session.time.justNow", "刚刚");
                m.put("session.time.minutesAgo", "{0} 分钟前");
                m.put("session.time.hoursAgo", "{0} 小时前");
                m.put("session.time.daysAgo", "{0} 天前");
                m.put("session.time.created", "创建: {0}");
                m.put("session.time.lastActive", "最后活跃: {0}");
                m.put("session.role.assistant", "AI");
                m.put("session.role.user", "用户");
                m.put("session.role.system", "系统");
                m.put("session.role.tool", "工具");
                m.put("session.role.unknown", "未知");

                // ── MCP 面板 ──
                m.put("mcp.table.empty", "暂无 MCP 服务器，点击 + 新增或从 CLI 导入");
                m.put("mcp.table.col.name", "名称");
                m.put("mcp.table.col.transport", "传输方式");
                m.put("mcp.table.col.detail", "详情");
                m.put("mcp.action.save", "保存更改");
                m.put("mcp.action.save.tooltip", "保存表格中的勾选状态变更");
                m.put("mcp.action.importCli", "从 CLI 导入");
                m.put("mcp.action.importCli.tooltip", "扫描 CLI 配置中已有的 MCP 服务器");
                m.put("mcp.dialog.deleteConfirm", "确定删除 MCP 服务器 \"{0}\" 吗？");
                m.put("mcp.dialog.deleteTitle", "确认删除");
                m.put("mcp.dialog.saveSuccess", "更改已保存并同步");
                m.put("mcp.dialog.saveTitle", "保存成功");
                m.put("mcp.dialog.importDone", "导入完成");
                m.put("mcp.import.newlyImported", "新增导入: {0}");
                m.put("mcp.import.mergedExisting", "合并已有: {0}");
                m.put("mcp.import.skippedInvalid", "跳过无效/冲突: {0}");
                m.put("mcp.import.nextStep", "下一步：勾选目标 CLI 列（如 OpenCode/Codex/Gemini）后点击保存更改即可同步安装。");
                m.put("mcp.import.warnings", "告警：");
                m.put("mcp.import.moreWarnings", "- ... 其余 {0} 条未展示");
                m.put("mcp.import.batchDone", "已导入 {0} 个 MCP 服务器");

                // ── Provider 面板 ──
                m.put("provider.filter.label", "筛选 CLI: ");
                m.put("provider.filter.all", "全部");
                m.put("provider.table.empty", "暂无配置，点击 '+' 新增");
                m.put("provider.table.col.name", "名称");
                m.put("provider.table.col.cli", "CLI");
                m.put("provider.table.col.status", "状态");
                m.put("provider.table.col.model", "模型");
                m.put("provider.status.active", "已激活");
                m.put("provider.status.pendingActivation", "待激活");
                m.put("provider.action.duplicate", "复制");
                m.put("provider.action.duplicate.tooltip", "复制选中的配置");
                m.put("provider.action.activate", "激活");
                m.put("provider.action.activate.tooltip", "激活选中的配置并同步到 CLI");
                m.put("provider.dialog.deleteConfirm", "确定删除配置 \"{0}\" 吗？");
                m.put("provider.dialog.deleteTitle", "确认删除");
                m.put("provider.dialog.activateSuccess", "配置 \"{0}\" 已激活\n已同步到 {1}");
                m.put("provider.dialog.activateTitle", "激活成功");
                m.put("provider.dialog.activateFailed", "激活失败: {0}");
                m.put("provider.dialog.error", "错误");
                m.put("provider.dialog.precheckFailedTitle", "连接预检失败");
                m.put("provider.dialog.precheckFailed", "连接测试未通过：{0}\n\n仍要继续激活吗？");
                m.put("provider.dialog.precheckContinue", "继续激活");
                m.put("provider.dialog.precheckCancel", "取消");

                // ── Prompt 面板 ──
                m.put("prompt.filter.label", "按 CLI 筛选: ");
                m.put("prompt.list.empty", "暂无预设，点击 '+' 新增");
                m.put("prompt.action.activate", "启用");
                m.put("prompt.action.activate.tooltip", "将此预设写入对应 CLI 的配置文件");
                m.put("prompt.button.loadCurrent", "加载当前配置");
                m.put("prompt.button.save", "保存内容");
                m.put("prompt.status.active", "  (已启用)");
                m.put("prompt.dialog.addTitle", "新增提示词预设");
                m.put("prompt.dialog.addPrompt", "预设名称:");
                m.put("prompt.dialog.noSelection", "请先选择一个预设。");
                m.put("prompt.dialog.noSelectionTitle", "未选择");
                m.put("prompt.dialog.savedAndSynced", "预设内容已保存并同步到 {0}。");
                m.put("prompt.dialog.saveTitle", "保存成功");
                m.put("prompt.dialog.syncFailed", "内容已保存，但同步到配置文件失败: {0}");
                m.put("prompt.dialog.syncFailedTitle", "同步失败");
                m.put("prompt.dialog.saved", "预设内容已保存。");
                m.put("prompt.dialog.deleteConfirm", "确定删除预设 \"{0}\" 吗？");
                m.put("prompt.dialog.deleteTitle", "确认删除");
                m.put("prompt.dialog.activateSuccess", "预设 \"{0}\" 已启用到 {1}。");
                m.put("prompt.dialog.activateTitle", "启用成功");
                m.put("prompt.dialog.activateFailed", "启用失败: {0}");
                m.put("prompt.dialog.loadConfirm", "加载当前配置将覆盖编辑器内容。\n确定继续吗？");
                m.put("prompt.dialog.loadTitle", "确认加载");

                // ── Skill 面板 ──
                m.put("skill.table.empty", "暂无已安装 Skills，点击 '+' 发现并安装，或点击 '扫描本地'");
                m.put("skill.table.col.name", "名称");
                m.put("skill.table.col.status", "状态");
                m.put("skill.table.col.desc", "描述");
                m.put("skill.table.repositoryPackageDesc", "包含 {0} 个 Skills");
                m.put("skill.table.repositoryChildUnowned", "已存在，未由仓库包管理");
                m.put("skill.status.installed", "已安装");
                m.put("skill.status.notInstalled", "未安装");
                m.put("skill.action.save", "保存更改");
                m.put("skill.action.save.tooltip", "保存表格中的勾选状态变更");
                m.put("skill.action.uninstall", "卸载 Skill");
                m.put("skill.action.remove", "移除");
                m.put("skill.action.scanLocal", "扫描本地");
                m.put("skill.action.scanLocal.tooltip", "扫描 ~/.config/coding-switch/skills 中已安装的 Skills");
                m.put("skill.action.discoverFromRepos", "发现 Skills");
                m.put("skill.action.addRepo", "添加仓库");
                m.put("skill.action.addRepo.tooltip", "添加自定义 GitHub 仓库（可选分支）");
                m.put("skill.action.manageRepo", "仓库管理");
                m.put("skill.action.manageRepo.tooltip", "新增、移除自定义仓库，并可设置分支");
                m.put("skill.action.configToken", "配置令牌");
                m.put("skill.action.configToken.tooltip", "配置 GitHub 令牌以提升 API 速率限制");
                m.put("skill.tokenConfig.title", "配置 GitHub 令牌");
                m.put("skill.action.discover", "发现 Skills");
                m.put("skill.action.discover.tooltip", "从仓库列表发现可安装 Skills");
                m.put("skill.action.install", "安装 Skill");
                m.put("skill.action.install.tooltip", "安装选中的 Skill 到本地");
                m.put("skill.action.update", "更新 Skill");
                m.put("skill.action.update.tooltip", "更新本地已安装的 Skill");
                m.put("skill.action.installZip", "ZIP 添加");
                m.put("skill.action.installZip.tooltip", "从本地 ZIP 包导入 Skill");
                m.put("skill.dialog.scanEmpty", "未在 ~/.config/coding-switch/skills 中发现已安装的 Skills");
                m.put("skill.dialog.scanDone", "扫描完成，发现 {0} 个本地 Skills。");
                m.put("skill.dialog.scanWithBridgeSyncFailed", "扫描完成，发现 {0} 个本地 Skills，但同步到 CLI 失败 {1} 个。\n详情：{2}");
                m.put("skill.dialog.scanTitle", "扫描结果");
                m.put("skill.dialog.uninstallConfirm", "卸载技能 \"{0}\" 吗？将删除本地目录。");
                m.put("skill.dialog.uninstallTitle", "确认卸载");
                m.put("skill.dialog.removeConfirm", "从列表移除并卸载 \"{0}\" 吗？此操作将删除其本地文件夹并取消所有 CLI 桥接。");
                m.put("skill.dialog.removeTitle", "确认移除");
                m.put("skill.dialog.removeChildNotSupported", "请选中仓库包行移除整个仓库，子 Skill 目前不支持单独移除。");
                m.put("skill.dialog.uninstallFailed", "卸载失败: {0}");
                m.put("skill.dialog.uninstallFailedWithTip", "卸载失败: {0}\n\n请关闭可能占用该目录的终端、文件管理器或杀毒软件后重试。");
                m.put("skill.dialog.addRepoPrompt",
                                "输入 GitHub 仓库地址（支持 owner/repo 或完整 URL）：\n（例：anthropics/skills 或 https://github.com/anthropics/skills）");
                m.put("skill.dialog.addRepoTitle", "添加自定义仓库");
                m.put("skill.dialog.addRepoDone", "仓库已添加: {0}");
                m.put("skill.dialog.addRepoSuccess", "成功");
                m.put("skill.dialog.repo.urlLabel", "仓库 URL:");
                m.put("skill.dialog.repo.branchLabel", "分支:");
                m.put("skill.dialog.repo.branchHint", "留空将使用仓库默认分支（如 main）");
                m.put("skill.repo.manager.title", "仓库管理");
                m.put("skill.repo.manager.add", "新增仓库");
                m.put("skill.repo.manager.remove", "移除仓库");
                m.put("skill.repo.manager.removeTitle", "确认移除");
                m.put("skill.repo.manager.removeConfirm", "确认移除仓库 \"{0}\" 吗？");
                m.put("skill.repo.manager.removeBuiltIn", "内置仓库不支持移除");
                m.put("skill.repo.manager.builtinTag", "内置");
                m.put("skill.dialog.saveSuccess", "Skill 同步状态已更新");
                m.put("skill.dialog.saveTitle", "保存成功");
                m.put("skill.dialog.bridgeSyncSuccess", "保存成功，已同步 {0} 个已勾选 CLI 的 Skill 配置。");
                m.put("skill.dialog.bridgeSyncPartial", "保存成功，但已勾选 CLI 的 Skill 配置同步部分失败。\n成功: {0}\n失败: {1}\n详情: {2}");
                m.put("skill.dialog.autoInstallSuccess", "保存成功，已自动安装 {0} 个 Skill，并同步到 {1} 个 CLI 的 Skills Bridge。");
                m.put("skill.dialog.autoInstallPartial", "保存成功，但自动安装部分失败。\n已安装: {0}\n失败: {1}\n详情: {2}");
                m.put("skill.dialog.autoInstallMissingRepo", "缺少仓库地址");
                m.put("skill.dialog.discoverTitle", "发现完成");
                m.put("skill.dialog.discoverDone", "新增: {0}\n刷新: {1}\n总计: {2}");
                m.put("skill.dialog.installTitle", "安装完成");
                m.put("skill.dialog.installSuccess", "Skill \"{0}\" 已安装");
                m.put("skill.dialog.installFailed", "安装失败: {0}");
                m.put("skill.dialog.installZipTitle", "ZIP 安装完成");
                m.put("skill.dialog.installZipSuccess", "Skill \"{0}\" 已通过 ZIP 安装");
                m.put("skill.dialog.installZipFailed", "ZIP 安装失败: {0}");
                m.put("skill.dialog.updateZipTitle", "ZIP 更新完成");
                m.put("skill.dialog.updateZipSuccess", "Skill \"{0}\" 已通过 ZIP 更新");
                m.put("skill.dialog.updateZipFailed", "ZIP 更新失败: {0}");
                m.put("skill.dialog.zipChooserTitle", "选择 Skill ZIP 包");
                m.put("skill.dialog.importZipTitle", "ZIP 添加完成");
                m.put("skill.dialog.importZipSuccess", "导入结果：{0}");
                m.put("skill.dialog.importZipFailed", "ZIP 导入失败: {0}");
                m.put("skill.dialog.gitRequired", "当前未检测到 git 命令，请先安装 Git，或使用 ZIP 安装。");
                m.put("skill.dialog.updateTitle", "更新完成");
                m.put("skill.dialog.updateSuccess", "Skill \"{0}\" 已更新");
                m.put("skill.dialog.updateFailed", "更新失败: {0}");

                m.put("skill.discovery.title", "发现 Skills 仓库");
                m.put("skill.discovery.label.repo", "选择仓库: ");
                m.put("skill.discovery.hint", "内置 2 个推荐仓库，也支持添加自定义仓库地址（可包含多个 skills 或单个 skill）。");
                m.put("skill.discovery.button.refresh", "刷新");
                m.put("skill.discovery.button.install", "安装整个仓库");
                m.put("skill.discovery.button.open", "打开地址");
                m.put("skill.discovery.button.close", "关闭");
                m.put("skill.discovery.col.skillName", "仓库内 Skills");
                m.put("skill.discovery.status.loading", "正在发现仓库...");
                m.put("skill.discovery.status.loaded", "当前仓库共 {0} 个技能");
                m.put("skill.discovery.status.loadFailed", "仓库发现失败: {0}");
                m.put("skill.discovery.status.rateLimited", "GitHub API rate limit exceeded. Retry later or configure a token.");
                m.put("skill.discovery.status.installing", "正在安装仓库: {0}");
                m.put("skill.discovery.status.installDone", "安装完成，成功 {0} / 跳过 {1} / 失败 {2}");
                m.put("skill.discovery.row.error", "异常: {0}");
                m.put("skill.discovery.row.empty", "未识别到可安装 skills");
                m.put("skill.discovery.row.ready", "可安装");
                m.put("skill.discovery.install.empty", "该仓库没有可安装的 skills。");
                m.put("skill.discovery.install.title", "安装完成");
                m.put("skill.discovery.install.success", "仓库 {0} 安装完成\n成功: {1}\n跳过: {2}\n失败: {3}");
                m.put("skill.discovery.install.failed", "仓库 {0} 安装失败: {1}");

                // ── McpServerDialog ──
                m.put("mcpDialog.title.edit", "编辑 MCP 服务器");
                m.put("mcpDialog.title.add", "新增 MCP 服务器");
                m.put("mcpDialog.label.name", "服务器名称:");
                m.put("mcpDialog.label.transport", "传输方式:");
                m.put("mcpDialog.label.syncTarget", "同步目标:");
                m.put("mcpDialog.label.command", "命令:");
                m.put("mcpDialog.label.args", "参数:");
                m.put("mcpDialog.label.argsHint", "空格分隔的参数（如 run main.js）");
                m.put("mcpDialog.label.urlHint", "如 http://localhost:8080/sse");
                m.put("mcpDialog.border.stdio", "STDIO 选项");
                m.put("mcpDialog.border.network", "网络选项");
                m.put("mcpDialog.tab.form", "表单模式");
                m.put("mcpDialog.tab.json", "JSON 模式");
                m.put("mcpDialog.json.hint", "<html>粘贴或编辑 JSON 格式的 MCP 服务器配置，支持以下格式：<br><br>" +
                                "<b>单个服务器（带名称）：</b><br>" +
                                "<code>{\"server-name\": {\"command\": \"npx\", \"args\": [\"-y\", \"pkg\"]}}</code><br><br>"
                                +
                                "<b>单个服务器（不带名称）：</b><br>" +
                                "<code>{\"command\": \"npx\", \"args\": [\"-y\", \"pkg\"]}</code><br><br>" +
                                "<b>多个服务器：</b><br>" +
                                "<code>{\"s1\": {\"command\": \"..\"}, \"s2\": {\"url\": \"...\"}}</code></html>");
                m.put("mcpDialog.validate.nameRequired", "请填写服务器名称");
                m.put("mcpDialog.validate.commandRequired", "STDIO 模式请填写命令");
                m.put("mcpDialog.validate.urlRequired", "请填写 URL");
                m.put("mcpDialog.validate.syncRequired", "请至少选择一个同步目标 CLI");
                m.put("mcpDialog.validate.jsonRequired", "请粘贴 JSON 配置");
                m.put("mcpDialog.validate.jsonFailed", "JSON 解析失败: {0}");
                m.put("mcpDialog.validate.jsonSingleServerEdit", "编辑模式下 JSON 仅支持单个 MCP 服务器");
                m.put("mcpDialog.json.noValidServer", "未找到有效的 MCP 服务器配置");

                // ── ProviderDialog ──
                m.put("providerDialog.title.edit", "编辑配置");
                m.put("providerDialog.title.add", "新增配置");
                m.put("providerDialog.preset.custom", "自定义");
                m.put("providerDialog.preset.officialHint", "💡 无需配置 API Key，激活后首次运行 CLI 将自动打开浏览器完成官方登录");
                m.put("providerDialog.preset.fillHint", "💡 已填充预设，请补充 API Key 后保存");
                m.put("providerDialog.label.preset", "预设配置:");
                m.put("providerDialog.label.cliType", "CLI 类型:");
                m.put("providerDialog.label.configName", "配置名称:");
                m.put("providerDialog.label.keyFieldName", "Key 字段名:");
                m.put("providerDialog.label.mainModel", "主模型:");
                m.put("providerDialog.label.model", "模型:");
                m.put("providerDialog.label.models", "模型列表:");
                m.put("providerDialog.button.addModel", "添加模型");
                m.put("providerDialog.tooltip.removeModel", "移除此模型");
                m.put("providerDialog.label.securityPolicy", "安全策略:");
                m.put("providerDialog.label.reasoningEffort", "推理强度:");
                m.put("providerDialog.label.1mContext", "1M 上下文");
                m.put("providerDialog.label.autoCompactWindow", "压缩窗口:");
                m.put("providerDialog.label.effortLevel", "推理强度:");
                m.put("providerDialog.label.alwaysThinkingEnabled", "扩展思考:");
                m.put("providerDialog.label.teamMode", "Team 模式");
                m.put("providerDialog.label.toolSearch", "Tool Search");
                m.put("providerDialog.label.disableAutoUpdater", "禁用自动升级");
                m.put("providerDialog.label.dangerousMode", "危险模式:");
                m.put("providerDialog.label.noFlickerMode", "TUI:");
                m.put("providerDialog.option.default", "默认");
                m.put("providerDialog.dangerousMode.skipPermissions", "跳过确认");
                m.put("providerDialog.dangerousMode.skipAll", "跳过确认和提示");
                m.put("providerDialog.noFlickerMode.enabled", "开启全屏");
                m.put("providerDialog.noFlickerMode.enabledDisableMouse", "开启但禁用鼠标");
                m.put("providerDialog.label.maxThinkingTokens", "思考令牌预算:");
                m.put("providerDialog.label.npmPackage", "NPM 包:");
                m.put("providerDialog.label.multiAgent", "多智能体");
                m.put("providerDialog.label.fastMode", "快速模式");
                m.put("providerDialog.border.claude", "Claude Code 配置");
                m.put("providerDialog.border.codex", "Codex 配置");
                m.put("providerDialog.border.gemini", "Gemini CLI 配置");
                m.put("providerDialog.border.opencode", "OpenCode 配置");
                m.put("providerDialog.validate.nameRequired", "请填写配置名称");
                m.put("providerDialog.validate.cliTypeRequired", "请选择 CLI 类型");
                m.put("providerDialog.validate.apiKeyRequired", "请填写 API Key");
                m.put("providerDialog.validate.baseUrlRequired", "请填写 Base URL");
                m.put("providerDialog.validate.modelRequired", "请填写主模型");
                m.put("providerDialog.button.testConnection", "测试连接");
                m.put("providerDialog.test.hint", "提示：少数供应商可能测试失败，但实际仍可正常使用。");
                m.put("providerDialog.test.validationTitle", "请先完成必填项");
                m.put("providerDialog.test.testing", "测试中...");
                m.put("providerDialog.test.success", "连接成功（{0}ms）");
                m.put("providerDialog.test.failed", "连接失败: {0}");
                m.put("providerDialog.test.skipOfficialLogin", "官方登录模式无需测试连接，激活后首次运行 CLI 会自动拉起登录流程。");
                m.put("providerDialog.test.skipOfficialLoginTitle", "无需测试连接");

                // ── ProviderDialog 预览 ──
                m.put("providerDialog.label.preview", "配置预览");
                m.put("providerDialog.preview.authJson", "auth.json");
                m.put("providerDialog.preview.configToml", "config.toml");
                m.put("providerDialog.preview.envFile", ".env");
                m.put("providerDialog.preview.settingsJson", "settings.json");
                m.put("providerDialog.button.showPreview", "配置预览");
                m.put("providerDialog.button.hidePreview", "收起");
                m.put("providerDialog.button.applyToForm", "应用到表单");
                m.put("providerDialog.button.openDir", "打开目录");
                m.put("providerDialog.openDir.notInstalled", "{0} 尚未安装，配置目录不存在。");
                m.put("providerDialog.openDir.failed", "打开目录失败: {0}");
                m.put("providerDialog.openDir.title", "提示");
                m.put("providerDialog.preview.parseError", "解析失败: {0}");
                m.put("providerDialog.preview.parseErrorTitle", "解析错误");

                m.put("provider.dialog.codexAuth.restored",
                                "\u5df2\u6062\u590d\u8be5 Codex \u8ba2\u9605\u914d\u7f6e\u7684\u767b\u5f55\u72b6\u6001\u3002");
                m.put("provider.dialog.codexAuth.loginRequired",
                                "\u5f53\u524d\u914d\u7f6e\u5c1a\u65e0\u5df2\u7ed1\u5b9a\u767b\u5f55\u72b6\u6001\uff0c\u4e0b\u6b21\u8fdb\u5165 Codex \u9700\u8981\u91cd\u65b0\u767b\u5f55\u3002");
                m.put("provider.dialog.codexAuth.snapshotInvalid",
                                "\u5386\u53f2\u767b\u5f55\u5feb\u7167\u5df2\u5931\u6548\uff0c\u5df2\u6e05\u7a7a\u4e3a\u672a\u767b\u5f55\u72b6\u6001\uff0c\u4e0b\u6b21\u8fdb\u5165 Codex \u9700\u8981\u91cd\u65b0\u767b\u5f55\u3002");

                // ── CLI Quick Launch ──
                m.put("settings.section.cliQuickLaunch", "CLI 快速启动");
                m.put("settings.label.enableCliQuickLaunch", "CLI 快速启动:");
                m.put("settings.option.enabled", "开启");
                m.put("settings.option.disabled", "关闭");
                m.put("settings.label.cliLaunchCommands", "启动命令列表");
                m.put("settings.button.addCliCommand", "添加");
                m.put("settings.button.removeCliCommand", "移除");
                m.put("settings.button.saveCliQuickLaunch", "保存");
                m.put("settings.dialog.cliCommand.addTitle", "添加 CLI 命令");
                m.put("settings.dialog.cliCommand.editTitle", "编辑 CLI 命令");
                m.put("settings.dialog.cliCommand.name", "名称:");
                m.put("settings.dialog.cliCommand.command", "命令:");
                m.put("settings.hint.cliQuickLaunch", "开启后，IDE 顶部工具栏将显示快速启动控件。单击：打开菜单。双击：快速启动当前选中的 CLI。通过本插件启动的 CLI，将支持通过 IDE 右键悬浮菜单插入文件路径和代码行号功能。");
                m.put("settings.table.col.name", "名称");
                m.put("settings.table.col.command", "命令");
                m.put("cliQuickLaunch.noCommand", "未配置命令");
                m.put("cliQuickLaunch.selectCommand", "选择 CLI");
                m.put("cliQuickLaunch.execute", "执行 {0}");
                m.put("cliQuickLaunch.action.text", "启动 CLI");
                m.put("cliQuickLaunch.insertFilePath", "插入文件路径");
                m.put("cliQuickLaunch.insertFilePath.description", "将当前文件或目录的相对路径插入到当前激活终端");
                m.put("cliQuickLaunch.insertFilePath.noProject", "当前未打开项目");
                m.put("cliQuickLaunch.insertFilePath.noActiveFile", "当前没有可插入的文件或目录");
                m.put("cliQuickLaunch.insertFilePath.fileOutOfProject", "当前文件或目录不在项目目录内");
                m.put("cliQuickLaunch.insertFilePath.noActiveTerminal", "当前没有可用的激活终端");
                m.put("cliQuickLaunch.insertFilePath.failed", "插入文件路径失败");
                m.put("cliQuickLaunch.insertFilePath.failedWithReason", "插入文件路径失败: {0}");
                m.put("cliQuickLaunch.insertFilePathWithLine", "插入带行号文件路径");
                m.put("cliQuickLaunch.insertFilePathWithLine.description", "将当前选中代码起始行的相对路径插入到当前激活终端");
                m.put("cliQuickLaunch.insertFilePathWithLine.noSelection", "当前没有选中的代码");
                m.put("cliQuickLaunch.toolbar.description", "双击快速启动：{0}");
                m.put("cliQuickLaunch.toolbar.noSelection", "当前未选择命令");
                m.put("cliQuickLaunch.saved", "CLI 快速启动配置已保存");
                m.put("cliQuickLaunch.savedTitle", "保存成功");

                return m;
        }

        private static Map<String, String> createEnMessages() {
                Map<String, String> m = new HashMap<>();

                // ── ToolWindow Tabs ──
                m.put("toolwindow.tab.providers", "Providers");
                m.put("toolwindow.tab.sessions", "Sessions");
                m.put("toolwindow.tab.mcp", "MCP");
                m.put("toolwindow.tab.skills", "Skills");
                m.put("toolwindow.tab.prompts", "Prompts");
                m.put("toolwindow.tab.settings", "Settings");
                m.put("common.button.ok", "OK");
                m.put("common.button.cancel", "Cancel");

                // ── Settings Panel ──
                m.put("settings.section.versionStatus", "CLI Version Status");
                m.put("settings.section.installCommands", "Install/Update Commands");
                m.put("settings.section.storageLocation", "Data Management");
                m.put("settings.section.preferences", "Preferences");
                m.put("settings.table.cli", "CLI");
                m.put("settings.table.currentVersion", "Current Version");
                m.put("settings.table.latestVersion", "Latest Version");
                m.put("settings.status.checking", "Checking...");
                m.put("settings.status.notInstalled", "Not Installed");
                m.put("settings.status.detectTimeout", "Detection Timeout");
                m.put("settings.status.detectFailed", "Detection Failed");
                m.put("settings.status.latest", "v{0} ✓ Up to date");
                m.put("settings.status.updatable", "v{0} ⬆ Update available");
                m.put("settings.status.localNewer", "v{0} / Local version is newer");
                m.put("settings.button.checkAllVersions", "Check All Versions");
                m.put("settings.tooltip.copyClipboard", "Copy to Clipboard");
                m.put("settings.label.uiLanguage", "UI Language:");
                m.put("settings.label.githubToken", "GitHub Token:");
                m.put("settings.label.dataStorageMode", "Storage Location:");
                m.put("settings.label.featureSelection", "Feature Selection:");
                m.put("settings.label.cliSelection", "CLI Config:");
                m.put("settings.button.show", "Show");
                m.put("settings.button.hide", "Hide");
                m.put("settings.button.configure", "Feature Config");
                m.put("settings.button.cliConfig", "CLI Config");
                m.put("settings.button.restoreDefault", "Restore Defaults");
                m.put("settings.button.saveGithubToken", "Save Token");
                m.put("settings.button.applyStorageMode", "Apply");
                m.put("settings.button.openStorageDirectory", "Open Directory");
                m.put("settings.hint.githubToken", "Optional. Increases GitHub API rate limit (only affects GitHub Skills repositories).");
                m.put("settings.hint.dataStorageMode", "Coding Switch stores configuration data in the current IDE's local directory by default. Switch to \"User Shared\" if you want to reuse configuration across JetBrains IDEs.");
                m.put("settings.hint.featureSelection", "Controls which top tabs are visible.");
                m.put("settings.hint.cliSelection", "Controls which CLI entries are shown in the version check and install/update command sections on this page. If none are selected, all CLIs are shown by default.");
                m.put("settings.githubToken.title", "Settings");
                m.put("settings.githubToken.saved", "GitHub token saved");
                m.put("settings.hint.restartRequired", "⚠️ Restart IDE after switching language for full effect");
                m.put("settings.dialog.languageChanged.message",
                                "Language switched to {0}\n\nRestart IDE for full effect.\nRestart now?");
                m.put("settings.dialog.languageChanged.title", "Language Changed");
                m.put("settings.dialog.languageChanged.restartNow", "Restart Now");
                m.put("settings.dialog.languageChanged.restartLater", "Later");
                m.put("settings.dialog.storageMode.title", "Storage Location Changed");
                m.put("settings.dialog.storageMode.detectTitle", "Existing User Shared Data Detected");
                m.put("settings.dialog.storageMode.confirmTitle", "Confirm Overwrite Strategy");
                m.put("settings.dialog.storageMode.appliedTitle", "Storage Location Updated");
                m.put("settings.dialog.storageMode.confirm", "Switching to {0} will migrate current Coding Switch data automatically.\n\nContinue?");
                m.put("settings.dialog.storageMode.confirmBackToLocal", "Switching to {0} will restore this IDE to its own local data copy.\n\nContinue?");
                m.put("settings.dialog.storageMode.confirmProceed", "Proceed");
                m.put("settings.dialog.storageMode.confirmYes", "Switch");
                m.put("settings.dialog.storageMode.confirmNo", "Cancel");
                m.put("settings.dialog.storageMode.option.localToShared", "Local overwrites shared");
                m.put("settings.dialog.storageMode.option.sharedToLocal", "Shared overwrites local");
                m.put("settings.dialog.storageMode.conflict",
                                "Existing user-shared data was detected.\n\nLocal total: {0}\nLocal breakdown: Provider {2} / Prompt {3} / Skill {4} / MCP {5}\n\nShared total: {1}\nShared breakdown: Provider {6} / Prompt {7} / Skill {8} / MCP {9}\n\nChoose which source to keep:");
                m.put("settings.dialog.storageMode.confirmLocalToShared",
                                "You are about to overwrite user-shared data with this IDE's local data.\n\nShared data after overwrite: {0} item(s)\nBreakdown: Provider {1} / Prompt {2} / Skill {3} / MCP {4}\n\nThis IDE will then switch to User Shared mode.\nContinue?");
                m.put("settings.dialog.storageMode.confirmSharedToLocal",
                                "You are about to overwrite this IDE's local data with the existing user-shared data.\n\nLocal data after overwrite: {0} item(s)\nBreakdown: Provider {1} / Prompt {2} / Skill {3} / MCP {4}\n\nThis IDE will then switch to User Shared mode.\nContinue?");
                m.put("settings.dialog.storageMode.failed", "Failed to switch data storage. Please try again.");
                m.put("settings.dialog.storageMode.applied", "Switched to {0}. Configuration data is now active immediately.");
                m.put("settings.dialog.storageDirectory.openFailed", "Failed to open data directory: {0}");
                m.put("settings.dialog.featureSelection.title", "Feature Selection");
                m.put("settings.dialog.featureSelection.enabled", "Enabled Features");
                m.put("settings.dialog.featureSelection.hidden", "Hidden Features");
                m.put("settings.dialog.featureSelection.settingsPinned", "Settings is always visible and cannot be hidden.");
                m.put("settings.dialog.cliSelection.title", "CLI Config");
                m.put("settings.dialog.cliSelection.enabled", "Visible CLIs");
                m.put("settings.dialog.cliSelection.hidden", "Hidden CLIs");
                m.put("settings.dialog.cliSelection.managedHint", "Only * marked CLIs support configuration management features");

                // ── Session Panel ──
                m.put("session.empty.selectHint", "Select a session to view details");
                m.put("session.loading.messages", "Loading messages...");
                m.put("session.loading.scanning", "Scanning sessions...");
                m.put("session.empty.noMessages", "No messages");
                m.put("session.empty.noSessions",
                                "<html><center>No sessions found<br><br><font size='2' color='gray'>Make sure you have installed and used at least one of<br>Claude Code, Codex, Gemini CLI or OpenCode.</font></center></html>");
                m.put("session.button.continueConversation", "Continue Conversation");
                m.put("session.button.delete", "Delete Session");
                m.put("session.terminal.continueTabName", "Continue Conversation");
                m.put("session.tooltip.refresh", "Refresh session list");
                m.put("session.tooltip.copy", "Copy: {0}");
                m.put("session.tooltip.delete", "Delete current session");
                m.put("session.tooltip.deleteUnsupported", "Session deletion is not supported for this CLI yet");
                m.put("session.dialog.deleteTitle", "Confirm Delete");
                m.put("session.dialog.deleteConfirm", "Delete session \"{0}\"? This action cannot be undone.");
                m.put("session.dialog.deleteUnsupported",
                                "Session deletion is not supported for this CLI.");
                m.put("session.dialog.deleteFailed", "Failed to delete session: {0}");
                m.put("session.dialog.continueFailed", "Failed to continue conversation: {0}");
                m.put("session.content.truncated", "\n... (content truncated)");
                m.put("session.time.unknown", "Unknown");
                m.put("session.time.justNow", "Just now");
                m.put("session.time.minutesAgo", "{0} min ago");
                m.put("session.time.hoursAgo", "{0} hr ago");
                m.put("session.time.daysAgo", "{0} days ago");
                m.put("session.time.created", "Created: {0}");
                m.put("session.time.lastActive", "Last active: {0}");
                m.put("session.role.assistant", "AI");
                m.put("session.role.user", "User");
                m.put("session.role.system", "System");
                m.put("session.role.tool", "Tool");
                m.put("session.role.unknown", "Unknown");

                // ── MCP Panel ──
                m.put("mcp.table.empty", "No MCP servers. Click + to add or import from CLI");
                m.put("mcp.table.col.name", "Name");
                m.put("mcp.table.col.transport", "Transport");
                m.put("mcp.table.col.detail", "Details");
                m.put("mcp.action.save", "Save Changes");
                m.put("mcp.action.save.tooltip", "Save checkbox state changes");
                m.put("mcp.action.importCli", "Import from CLI");
                m.put("mcp.action.importCli.tooltip", "Scan CLI configs for existing MCP servers");
                m.put("mcp.dialog.deleteConfirm", "Delete MCP server \"{0}\"?");
                m.put("mcp.dialog.deleteTitle", "Confirm Delete");
                m.put("mcp.dialog.saveSuccess", "Changes saved and synced");
                m.put("mcp.dialog.saveTitle", "Save Successful");
                m.put("mcp.dialog.importDone", "Import Complete");
                m.put("mcp.import.newlyImported", "Newly imported: {0}");
                m.put("mcp.import.mergedExisting", "Merged existing: {0}");
                m.put("mcp.import.skippedInvalid", "Skipped invalid/conflict: {0}");
                m.put("mcp.import.nextStep",
                                "Next: Check target CLI columns (e.g. OpenCode/Codex/Gemini) then click Save to sync.");
                m.put("mcp.import.warnings", "Warnings:");
                m.put("mcp.import.moreWarnings", "- ... {0} more not shown");
                m.put("mcp.import.batchDone", "Imported {0} MCP servers");

                // ── Provider Panel ──
                m.put("provider.filter.label", "Filter CLI: ");
                m.put("provider.filter.all", "All");
                m.put("provider.table.empty", "No configs. Click '+' to add");
                m.put("provider.table.col.name", "Name");
                m.put("provider.table.col.cli", "CLI");
                m.put("provider.table.col.status", "Status");
                m.put("provider.table.col.model", "Model");
                m.put("provider.status.active", "Active");
                m.put("provider.status.pendingActivation", "Pending Activation");
                m.put("provider.action.duplicate", "Duplicate");
                m.put("provider.action.duplicate.tooltip", "Duplicate selected config");
                m.put("provider.action.activate", "Activate");
                m.put("provider.action.activate.tooltip", "Activate selected config and sync to CLI");
                m.put("provider.dialog.deleteConfirm", "Delete config \"{0}\"?");
                m.put("provider.dialog.deleteTitle", "Confirm Delete");
                m.put("provider.dialog.activateSuccess", "Config \"{0}\" activated\nSynced to {1}");
                m.put("provider.dialog.activateTitle", "Activation Successful");
                m.put("provider.dialog.activateFailed", "Activation failed: {0}");
                m.put("provider.dialog.error", "Error");
                m.put("provider.dialog.precheckFailedTitle", "Connection Pre-check Failed");
                m.put("provider.dialog.precheckFailed", "Connection test failed: {0}\n\nContinue activation anyway?");
                m.put("provider.dialog.precheckContinue", "Continue Activation");
                m.put("provider.dialog.precheckCancel", "Cancel");

                // ── Prompt Panel ──
                m.put("prompt.filter.label", "Filter by CLI: ");
                m.put("prompt.list.empty", "No presets. Click '+' to add");
                m.put("prompt.action.activate", "Activate");
                m.put("prompt.action.activate.tooltip", "Write this preset to the CLI config file");
                m.put("prompt.button.loadCurrent", "Load Current Config");
                m.put("prompt.button.save", "Save Content");
                m.put("prompt.status.active", "  (Active)");
                m.put("prompt.dialog.addTitle", "New Prompt Preset");
                m.put("prompt.dialog.addPrompt", "Preset name:");
                m.put("prompt.dialog.noSelection", "Please select a preset first.");
                m.put("prompt.dialog.noSelectionTitle", "No Selection");
                m.put("prompt.dialog.savedAndSynced", "Preset saved and synced to {0}.");
                m.put("prompt.dialog.saveTitle", "Save Successful");
                m.put("prompt.dialog.syncFailed", "Content saved, but sync to config file failed: {0}");
                m.put("prompt.dialog.syncFailedTitle", "Sync Failed");
                m.put("prompt.dialog.saved", "Preset content saved.");
                m.put("prompt.dialog.deleteConfirm", "Delete preset \"{0}\"?");
                m.put("prompt.dialog.deleteTitle", "Confirm Delete");
                m.put("prompt.dialog.activateSuccess", "Preset \"{0}\" activated for {1}.");
                m.put("prompt.dialog.activateTitle", "Activation Successful");
                m.put("prompt.dialog.activateFailed", "Activation failed: {0}");
                m.put("prompt.dialog.loadConfirm", "Loading current config will overwrite editor content.\nContinue?");
                m.put("prompt.dialog.loadTitle", "Confirm Load");

                // ── Skill Panel ──
                m.put("skill.table.empty",
                                "No installed skills. Click '+' to discover and install, or use 'Scan Local'.");
                m.put("skill.table.col.name", "Name");
                m.put("skill.table.col.status", "Status");
                m.put("skill.table.col.desc", "Description");
                m.put("skill.table.repositoryPackageDesc", "{0} Skills");
                m.put("skill.table.repositoryChildUnowned", "Existing local skill, not managed by package");
                m.put("skill.status.installed", "Installed");
                m.put("skill.status.notInstalled", "Not Installed");
                m.put("skill.action.save", "Save Changes");
                m.put("skill.action.save.tooltip", "Save checkbox state changes");
                m.put("skill.action.uninstall", "Uninstall Skill");
                m.put("skill.action.remove", "Remove");
                m.put("skill.action.scanLocal", "Scan Local");
                m.put("skill.action.scanLocal.tooltip", "Scan ~/.config/coding-switch/skills for installed Skills");
                m.put("skill.action.discoverFromRepos", "Discover Skills");
                m.put("skill.action.addRepo", "Add Repository");
                m.put("skill.action.addRepo.tooltip", "Add a custom GitHub repository (optional branch)");
                m.put("skill.action.manageRepo", "Manage Repositories");
                m.put("skill.action.manageRepo.tooltip",
                                "Add or remove custom repositories, with optional branch configuration");
                m.put("skill.action.configToken", "Configure Token");
                m.put("skill.action.configToken.tooltip", "Configure GitHub token to increase API rate limit");
                m.put("skill.tokenConfig.title", "Configure GitHub Token");
                m.put("skill.action.discover", "Discover Skills");
                m.put("skill.action.discover.tooltip", "Discover installable skills from repositories");
                m.put("skill.action.install", "Install Skill");
                m.put("skill.action.install.tooltip", "Install selected skill locally");
                m.put("skill.action.update", "Update Skill");
                m.put("skill.action.update.tooltip", "Update selected installed skill");
                m.put("skill.action.installZip", "ZIP Add");
                m.put("skill.action.installZip.tooltip", "Import a Skill from local ZIP package");
                m.put("skill.dialog.scanEmpty", "No installed Skills found in ~/.config/coding-switch/skills");
                m.put("skill.dialog.scanDone", "Scan complete. Found {0} local Skills.");
                m.put("skill.dialog.scanWithBridgeSyncFailed", "Scan complete. Found {0} local Skills, but syncing to CLI failed for {1}.\nDetails: {2}");
                m.put("skill.dialog.scanTitle", "Scan Result");
                m.put("skill.dialog.uninstallConfirm",
                                "Uninstall skill \"{0}\"?\nThis will delete the local directory.");
                m.put("skill.dialog.uninstallTitle", "Confirm Uninstall");
                m.put("skill.dialog.removeConfirm",
                                "Remove and uninstall \"{0}\"? This will delete its local folder and cancel all CLI bridges.");
                m.put("skill.dialog.removeTitle", "Confirm Remove");
                m.put("skill.dialog.removeChildNotSupported",
                                "Select the repository package row to remove the whole repository. Removing a child skill separately is not supported yet.");
                m.put("skill.dialog.uninstallFailed", "Uninstall failed: {0}");
                m.put("skill.dialog.uninstallFailedWithTip",
                                "Uninstall failed: {0}\n\nPlease close terminals, file explorers, or antivirus processes that may lock this directory, then retry.");
                m.put("skill.dialog.addRepoPrompt",
                                "Enter GitHub repository address (owner/repo or full URL):\n(e.g. anthropics/skills or https://github.com/anthropics/skills)");
                m.put("skill.dialog.addRepoTitle", "Add Custom Repository");
                m.put("skill.dialog.addRepoDone", "Repository added: {0}");
                m.put("skill.dialog.addRepoSuccess", "Success");
                m.put("skill.dialog.repo.urlLabel", "Repository URL:");
                m.put("skill.dialog.repo.branchLabel", "Branch:");
                m.put("skill.dialog.repo.branchHint", "Leave blank to use repository default branch (e.g. main)");
                m.put("skill.repo.manager.title", "Repository Management");
                m.put("skill.repo.manager.add", "Add Repository");
                m.put("skill.repo.manager.remove", "Remove Repository");
                m.put("skill.repo.manager.removeTitle", "Confirm Remove");
                m.put("skill.repo.manager.removeConfirm", "Remove repository \"{0}\"?");
                m.put("skill.repo.manager.removeBuiltIn", "Built-in repositories cannot be removed");
                m.put("skill.repo.manager.builtinTag", "Built-in");
                m.put("skill.dialog.saveSuccess", "Skill sync status updated");
                m.put("skill.dialog.saveTitle", "Save Successful");
                m.put("skill.dialog.bridgeSyncSuccess", "Saved successfully. Synced skill configuration to {0} selected CLI(s).");
                m.put("skill.dialog.bridgeSyncPartial",
                                "Saved, but syncing selected CLI skill configuration partially failed.\nSuccess: {0}\nFailed: {1}\nDetails: {2}");
                m.put("skill.dialog.autoInstallSuccess",
                                "Saved. Auto-installed {0} skill(s) and synced Skills Bridge to {1} CLI(s).");
                m.put("skill.dialog.autoInstallPartial",
                                "Saved, but auto-install partially failed.\nInstalled: {0}\nFailed: {1}\nDetails: {2}");
                m.put("skill.dialog.autoInstallMissingRepo", "missing repository URL");
                m.put("skill.dialog.discoverTitle", "Discovery Complete");
                m.put("skill.dialog.discoverDone", "New: {0}\nRefreshed: {1}\nTotal: {2}");
                m.put("skill.dialog.installTitle", "Install Complete");
                m.put("skill.dialog.installSuccess", "Skill \"{0}\" installed");
                m.put("skill.dialog.installFailed", "Install failed: {0}");
                m.put("skill.dialog.installZipTitle", "ZIP Install Complete");
                m.put("skill.dialog.installZipSuccess", "Skill \"{0}\" installed via ZIP");
                m.put("skill.dialog.installZipFailed", "ZIP install failed: {0}");
                m.put("skill.dialog.updateZipTitle", "ZIP Update Complete");
                m.put("skill.dialog.updateZipSuccess", "Skill \"{0}\" updated via ZIP");
                m.put("skill.dialog.updateZipFailed", "ZIP update failed: {0}");
                m.put("skill.dialog.zipChooserTitle", "Select Skill ZIP Package");
                m.put("skill.dialog.importZipTitle", "ZIP Add Complete");
                m.put("skill.dialog.importZipSuccess", "Import result: {0}");
                m.put("skill.dialog.importZipFailed", "ZIP import failed: {0}");
                m.put("skill.dialog.gitRequired",
                                "Git command is not available. Install Git first or use ZIP install.");
                m.put("skill.dialog.updateTitle", "Update Complete");
                m.put("skill.dialog.updateSuccess", "Skill \"{0}\" updated");
                m.put("skill.dialog.updateFailed", "Update failed: {0}");

                m.put("skill.discovery.title", "Discover Skills Repositories");
                m.put("skill.discovery.label.repo", "Select Repository: ");
                m.put("skill.discovery.hint",
                                "Built-in 2 recommended repositories. You can also add a custom repository URL (supports repositories with multiple skills or a single skill).");
                m.put("skill.discovery.button.refresh", "Refresh");
                m.put("skill.discovery.button.install", "Install Repository");
                m.put("skill.discovery.button.open", "Open URL");
                m.put("skill.discovery.button.close", "Close");
                m.put("skill.discovery.col.skillName", "Repository Skills");
                m.put("skill.discovery.status.loading", "Discovering repositories...");
                m.put("skill.discovery.status.loaded", "{0} skills in current repository");
                m.put("skill.discovery.status.loadFailed", "Repository discovery failed: {0}");
                m.put("skill.discovery.status.rateLimited", "GitHub API rate limit exceeded. Retry later or configure a token.");
                m.put("skill.discovery.status.installing", "Installing repository: {0}");
                m.put("skill.discovery.status.installDone", "Install done, success {0} / skipped {1} / failed {2}");
                m.put("skill.discovery.row.error", "Error: {0}");
                m.put("skill.discovery.row.empty", "No installable skills found");
                m.put("skill.discovery.row.ready", "Ready");
                m.put("skill.discovery.install.empty", "No installable skills found in this repository.");
                m.put("skill.discovery.install.title", "Install Complete");
                m.put("skill.discovery.install.success",
                                "Repository {0} installed\nSuccess: {1}\nSkipped: {2}\nFailed: {3}");
                m.put("skill.discovery.install.failed", "Repository {0} install failed: {1}");

                // ── McpServerDialog ──
                m.put("mcpDialog.title.edit", "Edit MCP Server");
                m.put("mcpDialog.title.add", "Add MCP Server");
                m.put("mcpDialog.label.name", "Server Name:");
                m.put("mcpDialog.label.transport", "Transport:");
                m.put("mcpDialog.label.syncTarget", "Sync Target:");
                m.put("mcpDialog.label.command", "Command:");
                m.put("mcpDialog.label.args", "Args:");
                m.put("mcpDialog.label.argsHint", "Space-separated args (e.g. run main.js)");
                m.put("mcpDialog.label.urlHint", "e.g. http://localhost:8080/sse");
                m.put("mcpDialog.border.stdio", "STDIO Options");
                m.put("mcpDialog.border.network", "Network Options");
                m.put("mcpDialog.tab.form", "Form");
                m.put("mcpDialog.tab.json", "JSON");
                m.put("mcpDialog.json.hint", "<html>Paste or edit MCP server config in JSON format. Supported formats:<br><br>"
                                +
                                "<b>Single server (with name):</b><br>" +
                                "<code>{\"server-name\": {\"command\": \"npx\", \"args\": [\"-y\", \"pkg\"]}}</code><br><br>"
                                +
                                "<b>Single server (without name):</b><br>" +
                                "<code>{\"command\": \"npx\", \"args\": [\"-y\", \"pkg\"]}</code><br><br>" +
                                "<b>Multiple servers:</b><br>" +
                                "<code>{\"s1\": {\"command\": \"..\"}, \"s2\": {\"url\": \"...\"}}</code></html>");
                m.put("mcpDialog.validate.nameRequired", "Server name is required");
                m.put("mcpDialog.validate.commandRequired", "Command is required for STDIO mode");
                m.put("mcpDialog.validate.urlRequired", "URL is required");
                m.put("mcpDialog.validate.syncRequired", "Select at least one sync target CLI");
                m.put("mcpDialog.validate.jsonRequired", "Paste JSON config");
                m.put("mcpDialog.validate.jsonFailed", "JSON parse failed: {0}");
                m.put("mcpDialog.validate.jsonSingleServerEdit", "JSON edit mode only supports a single MCP server");
                m.put("mcpDialog.json.noValidServer", "No valid MCP server config found");

                // ── ProviderDialog ──
                m.put("providerDialog.title.edit", "Edit Config");
                m.put("providerDialog.title.add", "New Config");
                m.put("providerDialog.preset.custom", "Custom");
                m.put("providerDialog.preset.officialHint",
                                "💡 No API Key needed. CLI will open browser for official login on first run");
                m.put("providerDialog.preset.fillHint", "💡 Preset filled. Please add your API Key and save");
                m.put("providerDialog.label.preset", "Preset:");
                m.put("providerDialog.label.cliType", "CLI Type:");
                m.put("providerDialog.label.configName", "Config Name:");
                m.put("providerDialog.label.keyFieldName", "Key Field Name:");
                m.put("providerDialog.label.mainModel", "Main Model:");
                m.put("providerDialog.label.model", "Model:");
                m.put("providerDialog.label.models", "Models:");
                m.put("providerDialog.button.addModel", "Add Model");
                m.put("providerDialog.tooltip.removeModel", "Remove this model");
                m.put("providerDialog.label.securityPolicy", "Security Policy:");
                m.put("providerDialog.label.reasoningEffort", "Reasoning Effort:");
                m.put("providerDialog.label.1mContext", "1M Context");
                m.put("providerDialog.label.autoCompactWindow", "Compact Window:");
                m.put("providerDialog.label.effortLevel", "Effort Level:");
                m.put("providerDialog.label.alwaysThinkingEnabled", "Extended Thinking:");
                m.put("providerDialog.label.teamMode", "Team Mode");
                m.put("providerDialog.label.toolSearch", "Tool Search");
                m.put("providerDialog.label.disableAutoUpdater", "Disable Auto Update");
                m.put("providerDialog.label.dangerousMode", "Dangerous Mode:");
                m.put("providerDialog.label.noFlickerMode", "TUI:");
                m.put("providerDialog.option.default", "Default");
                m.put("providerDialog.dangerousMode.skipPermissions", "Skip Permissions");
                m.put("providerDialog.dangerousMode.skipAll", "Skip Permissions & Prompt");
                m.put("providerDialog.noFlickerMode.enabled", "Enable Fullscreen");
                m.put("providerDialog.noFlickerMode.enabledDisableMouse", "Enabled Without Mouse");
                m.put("providerDialog.label.maxThinkingTokens", "Max Thinking Tokens:");
                m.put("providerDialog.label.npmPackage", "NPM Package:");
                m.put("providerDialog.label.multiAgent", "Multi-Agent");
                m.put("providerDialog.label.fastMode", "Fast Mode");
                m.put("providerDialog.border.claude", "Claude Code Config");
                m.put("providerDialog.border.codex", "Codex Config");
                m.put("providerDialog.border.gemini", "Gemini CLI Config");
                m.put("providerDialog.border.opencode", "OpenCode Config");
                m.put("providerDialog.validate.nameRequired", "Config name is required");
                m.put("providerDialog.validate.cliTypeRequired", "CLI type is required");
                m.put("providerDialog.validate.apiKeyRequired", "API Key is required");
                m.put("providerDialog.validate.baseUrlRequired", "Base URL is required");
                m.put("providerDialog.validate.modelRequired", "Main model is required");
                m.put("providerDialog.button.testConnection", "Test Connection");
                m.put("providerDialog.test.hint",
                                "Note: Some providers may fail connection tests but still work in actual use.");
                m.put("providerDialog.test.validationTitle", "Missing Required Fields");
                m.put("providerDialog.test.testing", "Testing...");
                m.put("providerDialog.test.success", "Connection successful ({0}ms)");
                m.put("providerDialog.test.failed", "Connection failed: {0}");
                m.put("providerDialog.test.skipOfficialLogin",
                                "Official login mode does not require a connection test. The CLI will start its login flow on first run after activation.");
                m.put("providerDialog.test.skipOfficialLoginTitle", "Connection Test Not Needed");

                // ── ProviderDialog Preview ──
                m.put("providerDialog.label.preview", "Config Preview");
                m.put("providerDialog.preview.authJson", "auth.json");
                m.put("providerDialog.preview.configToml", "config.toml");
                m.put("providerDialog.preview.envFile", ".env");
                m.put("providerDialog.preview.settingsJson", "settings.json");
                m.put("providerDialog.button.showPreview", "Config Preview");
                m.put("providerDialog.button.hidePreview", "Hide");
                m.put("providerDialog.button.applyToForm", "Apply to Form");
                m.put("providerDialog.button.openDir", "Open Folder");
                m.put("providerDialog.openDir.notInstalled", "{0} is not installed. Config directory does not exist.");
                m.put("providerDialog.openDir.failed", "Failed to open folder: {0}");
                m.put("providerDialog.openDir.title", "Info");
                m.put("providerDialog.preview.parseError", "Parse error: {0}");
                m.put("providerDialog.preview.parseErrorTitle", "Parse Error");

                m.put("provider.dialog.codexAuth.restored",
                                "Restored the saved login state for this Codex subscription config.");
                m.put("provider.dialog.codexAuth.loginRequired",
                                "This config has no saved login state yet. Codex will ask you to sign in the next time it starts.");
                m.put("provider.dialog.codexAuth.snapshotInvalid",
                                "The saved login snapshot is no longer valid. It was cleared, and Codex will ask you to sign in next time.");

                // ── CLI Quick Launch ──
                m.put("settings.section.cliQuickLaunch", "CLI Quick Launch");
                m.put("settings.label.enableCliQuickLaunch", "CLI Quick Launch:");
                m.put("settings.option.enabled", "Enabled");
                m.put("settings.option.disabled", "Disabled");
                m.put("settings.label.cliLaunchCommands", "Launch Commands");
                m.put("settings.button.addCliCommand", "Add");
                m.put("settings.button.removeCliCommand", "Remove");
                m.put("settings.button.saveCliQuickLaunch", "Save");
                m.put("settings.dialog.cliCommand.addTitle", "Add CLI Command");
                m.put("settings.dialog.cliCommand.editTitle", "Edit CLI Command");
                m.put("settings.dialog.cliCommand.name", "Name:");
                m.put("settings.dialog.cliCommand.command", "Command:");
                m.put("settings.hint.cliQuickLaunch", "When enabled, a quick launch widget appears in the IDE toolbar. Single-click: open the menu. Double-click: quickly launch the currently selected CLI. CLIs launched through this plugin also support inserting file paths and code line numbers from the IDE right-click floating menu.");
                m.put("settings.table.col.name", "Name");
                m.put("settings.table.col.command", "Command");
                m.put("cliQuickLaunch.noCommand", "No command configured");
                m.put("cliQuickLaunch.selectCommand", "Select CLI");
                m.put("cliQuickLaunch.execute", "Execute {0}");
                m.put("cliQuickLaunch.action.text", "Launch CLI");
                m.put("cliQuickLaunch.insertFilePath", "Insert File Path");
                m.put("cliQuickLaunch.insertFilePath.description", "Insert the relative path of the current file or directory into the active terminal");
                m.put("cliQuickLaunch.insertFilePath.noProject", "No project is currently open");
                m.put("cliQuickLaunch.insertFilePath.noActiveFile", "No file or directory is available");
                m.put("cliQuickLaunch.insertFilePath.fileOutOfProject", "The current file or directory is outside the project directory");
                m.put("cliQuickLaunch.insertFilePath.noActiveTerminal", "No active terminal is available");
                m.put("cliQuickLaunch.insertFilePath.failed", "Failed to insert the file path");
                m.put("cliQuickLaunch.insertFilePath.failedWithReason", "Failed to insert the file path: {0}");
                m.put("cliQuickLaunch.insertFilePathWithLine", "Insert File Path with Line");
                m.put("cliQuickLaunch.insertFilePathWithLine.description", "Insert the relative path and starting line of the current selection into the active terminal");
                m.put("cliQuickLaunch.insertFilePathWithLine.noSelection", "No code is currently selected");
                m.put("cliQuickLaunch.toolbar.description", "Double-click to launch: {0}");
                m.put("cliQuickLaunch.toolbar.noSelection", "No command selected");
                m.put("cliQuickLaunch.saved", "CLI Quick Launch settings saved");
                m.put("cliQuickLaunch.savedTitle", "Saved");

                return m;
        }
}
