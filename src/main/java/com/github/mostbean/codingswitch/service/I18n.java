package com.github.mostbean.codingswitch.service;

import java.text.MessageFormat;
import java.util.EnumMap;
import java.util.HashMap;
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
            return PluginSettings.getInstance().getLanguage();
        } catch (Exception ignored) {
            return PluginSettings.Language.ZH;
        }
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

        // ── Settings 面板 ──
        m.put("settings.section.versionStatus", "CLI 版本状态");
        m.put("settings.section.installCommands", "安装/更新命令");
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
        m.put("settings.button.checkAllVersions", "检测全部版本");
        m.put("settings.tooltip.copyClipboard", "复制到剪贴板");
        m.put("settings.label.uiLanguage", "界面语言:");
        m.put("settings.hint.restartRequired", "⚠️ 切换语言后需要重启 IDE 才能完全生效");
        m.put("settings.dialog.languageChanged.message", "语言已切换为 {0}\n\n需要重启 IDE 才能完全生效。\n是否立即重启 IDE？");
        m.put("settings.dialog.languageChanged.title", "语言设置已更改");
        m.put("settings.dialog.languageChanged.restartNow", "立即重启");
        m.put("settings.dialog.languageChanged.restartLater", "稍后手动重启");

        // ── Session 面板 ──
        m.put("session.empty.selectHint", "选择一个会话查看详情");
        m.put("session.loading.messages", "加载消息中...");
        m.put("session.loading.scanning", "正在扫描会话...");
        m.put("session.empty.noMessages", "暂无消息记录");
        m.put("session.empty.noSessions",
                "<html><center>未发现任何会话<br><br><font size='2' color='gray'>请确保已安装并使用过 Claude Code、Codex、<br>Gemini CLI 或 OpenCode 中的至少一个工具。</font></center></html>");
        m.put("session.button.copyResumeCmd", "复制恢复命令");
        m.put("session.button.copyProjectDir", "复制项目目录");
        m.put("session.button.copied", "已复制 ✓");
        m.put("session.tooltip.refresh", "刷新会话列表");
        m.put("session.tooltip.copy", "复制: {0}");
        m.put("session.content.truncated", "\n... (内容过长已截断)");
        m.put("session.time.unknown", "未知");
        m.put("session.time.justNow", "刚刚");
        m.put("session.time.minutesAgo", "{0} 分钟前");
        m.put("session.time.hoursAgo", "{0} 小时前");
        m.put("session.time.daysAgo", "{0} 天前");
        m.put("session.time.created", "创建: {0}");
        m.put("session.time.lastActive", "最后活跃: {0}");

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
        m.put("skill.table.empty", "暂无 Skills，点击 '扫描本地' 发现已安装的技能");
        m.put("skill.table.col.name", "名称");
        m.put("skill.table.col.status", "状态");
        m.put("skill.table.col.desc", "描述");
        m.put("skill.status.installed", "已安装");
        m.put("skill.status.notInstalled", "未安装");
        m.put("skill.action.save", "保存更改");
        m.put("skill.action.save.tooltip", "保存表格中的勾选状态变更");
        m.put("skill.action.uninstall", "卸载 Skill");
        m.put("skill.action.scanLocal", "扫描本地");
        m.put("skill.action.scanLocal.tooltip", "扫描 ~/.claude/skills/ 中已安装的 Skills");
        m.put("skill.action.addRepo", "添加仓库");
        m.put("skill.action.addRepo.tooltip", "添加自定义 GitHub 仓库 URL");
        m.put("skill.dialog.scanEmpty", "未在 ~/.claude/skills/ 中发现已安装的 Skills");
        m.put("skill.dialog.scanTitle", "扫描结果");
        m.put("skill.dialog.uninstallConfirm", "卸载技能 \"{0}\" 吗？\n这将删除本地目录。");
        m.put("skill.dialog.uninstallTitle", "确认卸载");
        m.put("skill.dialog.uninstallFailed", "卸载失败: {0}");
        m.put("skill.dialog.addRepoPrompt", "输入 GitHub 仓库 URL：\n（例：https://github.com/anthropics/courses）");
        m.put("skill.dialog.addRepoTitle", "添加自定义仓库");
        m.put("skill.dialog.addRepoDone", "仓库已添加: {0}");
        m.put("skill.dialog.addRepoSuccess", "成功");
        m.put("skill.dialog.saveSuccess", "Skill 同步状态已更新");
        m.put("skill.dialog.saveTitle", "保存成功");

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
        m.put("mcpDialog.tab.json", "JSON 导入");
        m.put("mcpDialog.json.hint", "<html>粘贴 JSON 格式的 MCP 服务器配置，支持以下格式：<br><br>" +
                "<b>单个服务器（带名称）：</b><br>" +
                "<code>{\"server-name\": {\"command\": \"npx\", \"args\": [\"-y\", \"pkg\"]}}</code><br><br>" +
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
        m.put("providerDialog.label.reasoningEffort", "推理强度:");
        m.put("providerDialog.label.npmPackage", "NPM 包:");
        m.put("providerDialog.border.claude", "Claude Code 配置");
        m.put("providerDialog.border.codex", "Codex 配置");
        m.put("providerDialog.border.gemini", "Gemini CLI 配置");
        m.put("providerDialog.border.opencode", "OpenCode 配置");
        m.put("providerDialog.validate.nameRequired", "请填写配置名称");
        m.put("providerDialog.validate.apiKeyRequired", "请填写 API Key");

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

        // ── Settings Panel ──
        m.put("settings.section.versionStatus", "CLI Version Status");
        m.put("settings.section.installCommands", "Install/Update Commands");
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
        m.put("settings.button.checkAllVersions", "Check All Versions");
        m.put("settings.tooltip.copyClipboard", "Copy to Clipboard");
        m.put("settings.label.uiLanguage", "UI Language:");
        m.put("settings.hint.restartRequired", "⚠️ Restart IDE after switching language for full effect");
        m.put("settings.dialog.languageChanged.message",
                "Language switched to {0}\n\nRestart IDE for full effect.\nRestart now?");
        m.put("settings.dialog.languageChanged.title", "Language Changed");
        m.put("settings.dialog.languageChanged.restartNow", "Restart Now");
        m.put("settings.dialog.languageChanged.restartLater", "Later");

        // ── Session Panel ──
        m.put("session.empty.selectHint", "Select a session to view details");
        m.put("session.loading.messages", "Loading messages...");
        m.put("session.loading.scanning", "Scanning sessions...");
        m.put("session.empty.noMessages", "No messages");
        m.put("session.empty.noSessions",
                "<html><center>No sessions found<br><br><font size='2' color='gray'>Make sure you have installed and used at least one of<br>Claude Code, Codex, Gemini CLI or OpenCode.</font></center></html>");
        m.put("session.button.copyResumeCmd", "Copy Resume Command");
        m.put("session.button.copyProjectDir", "Copy Project Directory");
        m.put("session.button.copied", "Copied ✓");
        m.put("session.tooltip.refresh", "Refresh session list");
        m.put("session.tooltip.copy", "Copy: {0}");
        m.put("session.content.truncated", "\n... (content truncated)");
        m.put("session.time.unknown", "Unknown");
        m.put("session.time.justNow", "Just now");
        m.put("session.time.minutesAgo", "{0} min ago");
        m.put("session.time.hoursAgo", "{0} hr ago");
        m.put("session.time.daysAgo", "{0} days ago");
        m.put("session.time.created", "Created: {0}");
        m.put("session.time.lastActive", "Last active: {0}");

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
        m.put("skill.table.empty", "No Skills. Click 'Scan Local' to discover installed skills");
        m.put("skill.table.col.name", "Name");
        m.put("skill.table.col.status", "Status");
        m.put("skill.table.col.desc", "Description");
        m.put("skill.status.installed", "Installed");
        m.put("skill.status.notInstalled", "Not Installed");
        m.put("skill.action.save", "Save Changes");
        m.put("skill.action.save.tooltip", "Save checkbox state changes");
        m.put("skill.action.uninstall", "Uninstall Skill");
        m.put("skill.action.scanLocal", "Scan Local");
        m.put("skill.action.scanLocal.tooltip", "Scan ~/.claude/skills/ for installed Skills");
        m.put("skill.action.addRepo", "Add Repository");
        m.put("skill.action.addRepo.tooltip", "Add a custom GitHub repository URL");
        m.put("skill.dialog.scanEmpty", "No installed Skills found in ~/.claude/skills/");
        m.put("skill.dialog.scanTitle", "Scan Result");
        m.put("skill.dialog.uninstallConfirm", "Uninstall skill \"{0}\"?\nThis will delete the local directory.");
        m.put("skill.dialog.uninstallTitle", "Confirm Uninstall");
        m.put("skill.dialog.uninstallFailed", "Uninstall failed: {0}");
        m.put("skill.dialog.addRepoPrompt",
                "Enter GitHub repository URL:\n(e.g. https://github.com/anthropics/courses)");
        m.put("skill.dialog.addRepoTitle", "Add Custom Repository");
        m.put("skill.dialog.addRepoDone", "Repository added: {0}");
        m.put("skill.dialog.addRepoSuccess", "Success");
        m.put("skill.dialog.saveSuccess", "Skill sync status updated");
        m.put("skill.dialog.saveTitle", "Save Successful");

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
        m.put("mcpDialog.tab.json", "JSON Import");
        m.put("mcpDialog.json.hint", "<html>Paste MCP server config in JSON format. Supported formats:<br><br>" +
                "<b>Single server (with name):</b><br>" +
                "<code>{\"server-name\": {\"command\": \"npx\", \"args\": [\"-y\", \"pkg\"]}}</code><br><br>" +
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
        m.put("providerDialog.label.reasoningEffort", "Reasoning Effort:");
        m.put("providerDialog.label.npmPackage", "NPM Package:");
        m.put("providerDialog.border.claude", "Claude Code Config");
        m.put("providerDialog.border.codex", "Codex Config");
        m.put("providerDialog.border.gemini", "Gemini CLI Config");
        m.put("providerDialog.border.opencode", "OpenCode Config");
        m.put("providerDialog.validate.nameRequired", "Config name is required");
        m.put("providerDialog.validate.apiKeyRequired", "API Key is required");

        return m;
    }
}
