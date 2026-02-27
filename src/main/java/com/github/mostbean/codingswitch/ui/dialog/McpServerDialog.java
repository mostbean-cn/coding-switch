package com.github.mostbean.codingswitch.ui.dialog;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.model.McpServer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Server 编辑对话框。
 * 支持两种模式：表单填写 和 JSON 粘贴导入。
 */
public class McpServerDialog extends DialogWrapper {

    // 表单字段
    private final JTextField nameField = new JTextField(30);
    private final JComboBox<McpServer.TransportType> transportCombo = new JComboBox<>(McpServer.TransportType.values());
    private final JTextField commandField = new JTextField(30);
    private final JTextField argsField = new JTextField(30);
    private final JTextField urlField = new JTextField(30);
    private final Map<CliType, JBCheckBox> syncChecks = new HashMap<>();

    // JSON 导入字段
    private final JTextArea jsonInput = new JTextArea(12, 40);

    private final JPanel transportDynamicPanel = new JPanel(new CardLayout());
    private final JBTabbedPane tabbedPane = new JBTabbedPane();
    private final McpServer server;
    private final boolean isEdit;

    // JSON 导入可能产生多个服务器
    private List<McpServer> parsedServers = null;

    public McpServerDialog(@Nullable McpServer existing) {
        super(true);
        this.server = existing != null ? existing : new McpServer();
        this.isEdit = existing != null;

        setTitle(isEdit ? "编辑 MCP 服务器" : "新增 MCP 服务器");

        for (CliType cliType : CliType.values()) {
            JBCheckBox cb = new JBCheckBox(cliType.getDisplayName());
            cb.setSelected(true);
            syncChecks.put(cliType, cb);
        }

        transportDynamicPanel.add(buildStdioPanel(), McpServer.TransportType.STDIO.name());
        transportDynamicPanel.add(buildUrlPanel(), McpServer.TransportType.SSE.name());
        transportDynamicPanel.add(buildUrlPanel(), McpServer.TransportType.HTTP.name());

        transportCombo.addActionListener(e -> {
            McpServer.TransportType selected = (McpServer.TransportType) transportCombo.getSelectedItem();
            if (selected != null) {
                ((CardLayout) transportDynamicPanel.getLayout()).show(transportDynamicPanel, selected.name());
            }
        });

        if (isEdit) {
            nameField.setText(existing.getName());
            transportCombo.setSelectedItem(existing.getTransportType());
            if (existing.getTransportType() == McpServer.TransportType.STDIO) {
                commandField.setText(existing.getCommand());
                if (existing.getArgs() != null) {
                    argsField.setText(String.join(" ", existing.getArgs()));
                }
            } else {
                urlField.setText(existing.getUrl());
            }
            for (CliType cli : CliType.values()) {
                syncChecks.get(cli).setSelected(existing.isSyncedTo(cli));
            }
        }

        init();

        McpServer.TransportType initial = (McpServer.TransportType) transportCombo.getSelectedItem();
        if (initial != null) {
            ((CardLayout) transportDynamicPanel.getLayout()).show(transportDynamicPanel, initial.name());
        }
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        // ===== 表单模式 =====
        JPanel syncPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        syncPanel.setBorder(JBUI.Borders.empty());
        for (CliType cli : CliType.values()) {
            syncPanel.add(syncChecks.get(cli));
        }

        JPanel formPanel = FormBuilder.createFormBuilder()
                .addLabeledComponent("服务器名称:", nameField)
                .addLabeledComponent("传输方式:", transportCombo)
                .addLabeledComponent("同步目标:", syncPanel)
                .addComponent(transportDynamicPanel)
                .getPanel();
        formPanel.setBorder(JBUI.Borders.empty(4, 8, 4, 8));

        // ===== JSON 导入模式 =====
        JPanel jsonPanel = new JPanel(new BorderLayout(0, 8));
        jsonPanel.setBorder(JBUI.Borders.empty(8));

        JBLabel hint = new JBLabel("<html>" +
                "粘贴 JSON 格式的 MCP 服务器配置，支持以下格式：<br><br>" +
                "<b>单个服务器（带名称）：</b><br>" +
                "<code>{\"server-name\": {\"command\": \"npx\", \"args\": [\"-y\", \"pkg\"]}}</code><br><br>" +
                "<b>单个服务器（不带名称）：</b><br>" +
                "<code>{\"command\": \"npx\", \"args\": [\"-y\", \"pkg\"]}</code><br><br>" +
                "<b>多个服务器：</b><br>" +
                "<code>{\"s1\": {\"command\": \"..\"}, \"s2\": {\"url\": \"...\"}}</code>" +
                "</html>");
        hint.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);

        jsonInput.setFont(new Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12)));
        jsonInput.setLineWrap(true);
        jsonInput.setWrapStyleWord(true);
        if (!isEdit) {
            jsonInput.setText(
                    "{\n  \"server-name\": {\n    \"command\": \"npx\",\n    \"args\": [\"-y\", \"@anthropic-ai/mcp-server-filesystem\"]\n  }\n}");
            jsonInput.selectAll();
        }

        jsonPanel.add(hint, BorderLayout.NORTH);
        jsonPanel.add(new JScrollPane(jsonInput), BorderLayout.CENTER);

        // ===== 标签页 =====
        tabbedPane.addTab("表单模式", formPanel);
        if (!isEdit) {
            tabbedPane.addTab("JSON 导入", jsonPanel);
        }

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setPreferredSize(new Dimension(JBUI.scale(500), JBUI.scale(400)));
        wrapper.add(tabbedPane, BorderLayout.CENTER);
        return wrapper;
    }

    private JPanel buildStdioPanel() {
        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent("命令:", commandField)
                .addLabeledComponent("参数:", argsField)
                .addTooltip("空格分隔的参数（如 run main.js）")
                .getPanel();
        return wrapWithTitledBorder(form, "STDIO 选项");
    }

    private JPanel buildUrlPanel() {
        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent("URL:", urlField)
                .addTooltip("如 http://localhost:8080/sse")
                .getPanel();
        return wrapWithTitledBorder(form, "网络选项");
    }

    private JPanel wrapWithTitledBorder(JPanel content, String title) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), title));
        content.setBorder(JBUI.Borders.empty(8, 12, 12, 12));
        wrapper.add(content, BorderLayout.CENTER);
        return wrapper;
    }

    // =====================================================================
    // 校验
    // =====================================================================

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (isJsonMode()) {
            return validateJson();
        }
        return validateForm();
    }

    private boolean isJsonMode() {
        return tabbedPane.getSelectedIndex() == 1;
    }

    private ValidationInfo validateForm() {
        if (nameField.getText().isBlank()) {
            return new ValidationInfo("请填写服务器名称", nameField);
        }
        McpServer.TransportType type = (McpServer.TransportType) transportCombo.getSelectedItem();
        if (type == McpServer.TransportType.STDIO) {
            if (commandField.getText().isBlank()) {
                return new ValidationInfo("STDIO 模式请填写命令", commandField);
            }
        } else {
            if (urlField.getText().isBlank()) {
                return new ValidationInfo("请填写 URL", urlField);
            }
        }
        boolean hasSync = syncChecks.values().stream().anyMatch(JBCheckBox::isSelected);
        if (!hasSync) {
            return new ValidationInfo("请至少选择一个同步目标 CLI");
        }
        return null;
    }

    private ValidationInfo validateJson() {
        String text = jsonInput.getText().trim();
        if (text.isEmpty()) {
            return new ValidationInfo("请粘贴 JSON 配置", jsonInput);
        }
        try {
            parseJsonInput(text);
        } catch (Exception e) {
            return new ValidationInfo("JSON 解析失败: " + e.getMessage(), jsonInput);
        }
        return null;
    }

    // =====================================================================
    // 结果获取
    // =====================================================================

    /**
     * 获取单个服务器（表单模式 或 JSON 单服务器时使用）
     */
    public McpServer getServer() {
        if (isJsonMode() && parsedServers != null && !parsedServers.isEmpty()) {
            return parsedServers.get(0);
        }
        return buildFromForm();
    }

    /**
     * 获取所有服务器（JSON 导入可能产生多个）
     */
    public List<McpServer> getServers() {
        if (isJsonMode() && parsedServers != null) {
            return parsedServers;
        }
        return List.of(buildFromForm());
    }

    public boolean isMultipleServers() {
        return isJsonMode() && parsedServers != null && parsedServers.size() > 1;
    }

    private McpServer buildFromForm() {
        server.setName(nameField.getText().trim());
        McpServer.TransportType type = (McpServer.TransportType) transportCombo.getSelectedItem();
        server.setTransportType(type);

        if (type == McpServer.TransportType.STDIO) {
            server.setCommand(commandField.getText().trim());
            String argsStr = argsField.getText().trim();
            server.setArgs(argsStr.isEmpty() ? new String[0] : argsStr.split("\\s+"));
            server.setUrl(null);
        } else {
            server.setUrl(urlField.getText().trim());
            server.setCommand(null);
            server.setArgs(null);
        }

        for (CliType cli : CliType.values()) {
            server.setSyncedTo(cli, syncChecks.get(cli).isSelected());
        }
        return server;
    }

    // =====================================================================
    // JSON 解析
    // =====================================================================

    private void parseJsonInput(String text) {
        parsedServers = new ArrayList<>();
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();

        // 判断：如果有 command/url 字段 → 单个无名服务器
        if (root.has("command") || root.has("url")) {
            McpServer s = parseOneServer("imported-server", root);
            parsedServers.add(s);
            return;
        }

        // 否则：key 是服务器名，value 是配置
        for (String name : root.keySet()) {
            if (!root.get(name).isJsonObject())
                continue;
            McpServer s = parseOneServer(name, root.getAsJsonObject(name));
            parsedServers.add(s);
        }

        if (parsedServers.isEmpty()) {
            throw new IllegalArgumentException("未找到有效的 MCP 服务器配置");
        }
    }

    private McpServer parseOneServer(String name, JsonObject json) {
        McpServer s = new McpServer();
        s.setName(name);
        s.setEnabled(true);
        for (CliType cli : CliType.values()) {
            s.setSyncedTo(cli, true);
        }

        if (json.has("command")) {
            s.setTransportType(McpServer.TransportType.STDIO);
            s.setCommand(json.get("command").getAsString());
            if (json.has("args") && json.get("args").isJsonArray()) {
                JsonArray arr = json.getAsJsonArray("args");
                String[] args = new String[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    args[i] = arr.get(i).getAsString();
                }
                s.setArgs(args);
            }
        } else if (json.has("url")) {
            String url = json.get("url").getAsString();
            s.setTransportType(url.contains("/sse") ? McpServer.TransportType.SSE : McpServer.TransportType.HTTP);
            s.setUrl(url);
        }

        if (json.has("env") && json.get("env").isJsonObject()) {
            Map<String, String> env = new HashMap<>();
            JsonObject envJson = json.getAsJsonObject("env");
            for (String key : envJson.keySet()) {
                env.put(key, envJson.get(key).getAsString());
            }
            s.setEnv(env);
        }

        return s;
    }
}
