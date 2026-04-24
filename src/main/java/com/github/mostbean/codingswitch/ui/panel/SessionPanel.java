package com.github.mostbean.codingswitch.ui.panel;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.model.SessionMessage;
import com.github.mostbean.codingswitch.model.SessionMeta;
import com.github.mostbean.codingswitch.service.I18n;
import com.github.mostbean.codingswitch.service.PluginSettings;
import com.github.mostbean.codingswitch.service.SessionScannerService;
import com.github.mostbean.codingswitch.ui.action.TerminalSessionService;
import com.github.mostbean.codingswitch.ui.dialog.BatchDeleteSessionsDialog;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 会话管理面板。
 * 左侧为会话列表（带搜索过滤），右侧为会话详情（消息时间线 + 操作按钮）。
 */
public class SessionPanel extends JPanel {

    private static final long AUTO_REFRESH_INTERVAL_MS = 5 * 60 * 1000L;
    private static final CliType DEFAULT_SESSION_FILTER_CLI = CliType.CLAUDE;

    private final DefaultListModel<SessionMeta> listModel = new DefaultListModel<>();
    private final JBList<SessionMeta> sessionList = new JBList<>(listModel);
    // 重写 getPreferredSize/getMinimumSize 防止右侧长文本内容挤压左侧面板
    private final JPanel detailPanel = new JPanel(new BorderLayout()) {
        @Override
        public Dimension getPreferredSize() {
            return new Dimension(0, super.getPreferredSize().height);
        }

        @Override
        public Dimension getMinimumSize() {
            return new Dimension(0, 0);
        }
    };
    private final JPanel messageContainer = new JPanel();
    private final JBLabel emptyLabel = new JBLabel(I18n.t("session.empty.selectHint"), SwingConstants.CENTER);
    private final Project project;

    private List<SessionMeta> allSessions = new ArrayList<>();
    private String searchQuery = "";
    private String selectedProvider = "claude"; // 默认 Claude Code
    private CliType selectedCliType = DEFAULT_SESSION_FILTER_CLI;
    private final AtomicBoolean refreshInProgress = new AtomicBoolean(false);
    private volatile long lastRefreshCompletedAt = -1L;

    public SessionPanel(Project project) {
        super(new BorderLayout());
        this.project = project;

        JPanel leftPanel = createLeftPanel();
        leftPanel.setMinimumSize(new Dimension(220, 0));

        JPanel rightPanel = createRightPanel();
        rightPanel.setMinimumSize(new Dimension(0, 0));

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
        splitPane.setDividerLocation(260);
        splitPane.setResizeWeight(0.35);
        splitPane.setContinuousLayout(true);
        splitPane.setDividerSize(1);
        splitPane.setBorder(null);
        add(splitPane, BorderLayout.CENTER);

        // 初次创建面板时自动刷新一次，后续进入页面走 5 分钟冷却策略
        autoRefreshOnEntry();
    }

    // =====================================================================
    // 左侧面板：搜索 + 会话列表
    // =====================================================================

    private JPanel createLeftPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.emptyRight(4));

        // 顶部区域：过滤下拉框 + 批量删除按钮 + 搜索框 + 刷新按钮
        JPanel topArea = new JPanel();
        topArea.setLayout(new BoxLayout(topArea, BoxLayout.Y_AXIS));
        topArea.setBorder(JBUI.Borders.emptyBottom(4));

        // 第一行：过滤下拉框 + 批量删除按钮
        JPanel filterBar = new JPanel(new BorderLayout(4, 0));
        filterBar.setBorder(JBUI.Borders.emptyBottom(4));
        filterBar.setAlignmentX(Component.LEFT_ALIGNMENT);

        JComboBox<CliType> filterCombo = new JComboBox<>();
        List<CliType> visibleCliTypes = PluginSettings.getInstance().getVisibleManagedCliTypes();
        for (CliType cliType : visibleCliTypes) {
            filterCombo.addItem(cliType);
        }
        CliType savedCli = PluginSettings.getInstance().getSessionFilterCli();
        CliType initialCli = visibleCliTypes.contains(savedCli)
                ? savedCli
                : (visibleCliTypes.contains(DEFAULT_SESSION_FILTER_CLI)
                    ? DEFAULT_SESSION_FILTER_CLI
                    : (visibleCliTypes.isEmpty() ? null : visibleCliTypes.get(0)));
        filterCombo.setSelectedItem(initialCli);
        selectedCliType = initialCli;
        selectedProvider = initialCli == null ? "" : initialCli.getId();
        filterCombo.addActionListener(e -> {
            CliType selectedCli = (CliType) filterCombo.getSelectedItem();
            if (selectedCli != null) {
                selectedCliType = selectedCli;
                selectedProvider = selectedCli.getId();
                PluginSettings.getInstance().setSessionFilterCli(selectedCli);
                applyFilter();
            }
        });
        filterBar.add(filterCombo, BorderLayout.CENTER);

        JButton batchDeleteBtn = new JButton(I18n.t("session.button.batchDelete"));
        batchDeleteBtn.setIcon(AllIcons.General.Remove);
        batchDeleteBtn.addActionListener(e -> onBatchDelete());
        filterBar.add(batchDeleteBtn, BorderLayout.EAST);

        topArea.add(filterBar);

        // 第二行：搜索框 + 刷新按钮
        SearchTextField searchField = new SearchTextField();
        searchField.addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onSearchChanged(searchField.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onSearchChanged(searchField.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onSearchChanged(searchField.getText());
            }
        });
        JPanel searchRow = new JPanel(new BorderLayout(4, 0));
        searchRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        searchRow.add(searchField, BorderLayout.CENTER);

        JButton refreshBtn = new JButton(AllIcons.Actions.Refresh);
        refreshBtn.setToolTipText(I18n.t("session.tooltip.refresh"));
        refreshBtn.addActionListener(e -> refreshSessions(true));
        searchRow.add(refreshBtn, BorderLayout.EAST);
        topArea.add(searchRow);

        panel.add(topArea, BorderLayout.NORTH);

        // 列表
        sessionList.setCellRenderer(new SessionListCellRenderer());
        sessionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sessionList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                SessionMeta selected = sessionList.getSelectedValue();
                if (selected != null)
                    showDetail(selected);
            }
        });

        panel.add(new JBScrollPane(sessionList), BorderLayout.CENTER);

        // 底部状态
        return panel;
    }

    // =====================================================================
    // 右侧面板：会话详情
    // =====================================================================

    private JPanel createRightPanel() {
        detailPanel.setBorder(JBUI.Borders.emptyLeft(4));

        // 默认空态
        emptyLabel.setForeground(UIUtil.getInactiveTextColor());
        detailPanel.add(emptyLabel, BorderLayout.CENTER);

        return detailPanel;
    }

    private void showDetail(SessionMeta session) {
        detailPanel.removeAll();

        // 顶部：元信息
        JPanel metaPanel = createMetaPanel(session);
        detailPanel.add(metaPanel, BorderLayout.NORTH);

        // 中部：消息时间线（先显示加载中）
        messageContainer.removeAll();
        messageContainer.setLayout(new BoxLayout(messageContainer, BoxLayout.Y_AXIS));
        JBLabel loadingLabel = new JBLabel(I18n.t("session.loading.messages"), SwingConstants.CENTER);
        loadingLabel.setForeground(UIUtil.getInactiveTextColor());
        messageContainer.add(loadingLabel);

        JBScrollPane scrollPane = new JBScrollPane(messageContainer);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        detailPanel.add(scrollPane, BorderLayout.CENTER);

        // 底部：操作按钮
        JPanel actionPanel = createActionPanel(session);
        detailPanel.add(actionPanel, BorderLayout.SOUTH);

        detailPanel.revalidate();
        detailPanel.repaint();

        // 异步加载消息
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<SessionMessage> messages = SessionScannerService.getInstance()
                    .loadMessages(session.getProviderId(), session.getSourcePath());
            SwingUtilities.invokeLater(() -> renderMessages(messages));
        });
    }

    private JPanel createMetaPanel(SessionMeta session) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(JBUI.Borders.empty(6, 8, 10, 8));

        // 标题行（超长截断，显示 tooltip）
        String displayTitle = session.getDisplayTitle();
        JBLabel titleLabel = new JBLabel(displayTitle);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, titleLabel.getPreferredSize().height));
        titleLabel.setToolTipText(displayTitle);
        panel.add(titleLabel);
        panel.add(Box.createVerticalStrut(4));

        // CLI 类型 + 会话 ID
        String cliName = getCliDisplayName(session.getProviderId());
        JBLabel cliLabel = new JBLabel(cliName + "  ·  " + truncateId(session.getSessionId()));
        cliLabel.setFont(cliLabel.getFont().deriveFont(11f));
        cliLabel.setForeground(UIUtil.getInactiveTextColor());
        cliLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(cliLabel);

        // 恢复命令（带一键复制）
        if (session.getResumeCommand() != null && !session.getResumeCommand().isBlank()) {
            panel.add(Box.createVerticalStrut(2));
            panel.add(createCopyableRow("▶ " + session.getResumeCommand(), session.getResumeCommand()));
        }

        // 项目目录（带一键复制）
        if (session.getProjectDir() != null && !session.getProjectDir().isBlank()) {
            panel.add(Box.createVerticalStrut(2));
            panel.add(createCopyableRow("📁 " + session.getProjectDir(), session.getProjectDir()));
        }

        // 时间范围
        String timeRange = formatTimeRange(session.getCreatedAt(), session.getLastActiveAt());
        if (!timeRange.isBlank()) {
            panel.add(Box.createVerticalStrut(2));
            JBLabel timeLabel = new JBLabel("🕐 " + timeRange);
            timeLabel.setFont(timeLabel.getFont().deriveFont(11f));
            timeLabel.setForeground(UIUtil.getInactiveTextColor());
            timeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(timeLabel);
        }

        // 分隔线
        panel.add(Box.createVerticalStrut(6));
        JSeparator sep = new JSeparator();
        sep.setAlignmentX(Component.LEFT_ALIGNMENT);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        panel.add(sep);

        return panel;
    }

    /**
     * 创建一行可复制的信息：左侧显示文本，右侧带复制小按钮。
     */
    private JPanel createCopyableRow(String displayText, String copyValue) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));

        JBLabel label = new JBLabel(displayText);
        label.setFont(label.getFont().deriveFont(11f));
        label.setForeground(UIUtil.getInactiveTextColor());
        row.add(label, BorderLayout.CENTER);

        JButton copyBtn = new JButton(AllIcons.Actions.Copy);
        copyBtn.setToolTipText(I18n.t("session.tooltip.copy", copyValue));
        copyBtn.setPreferredSize(new Dimension(20, 20));
        copyBtn.setMargin(JBUI.emptyInsets());
        copyBtn.setBorderPainted(false);
        copyBtn.setContentAreaFilled(false);
        copyBtn.addActionListener(e -> {
            copyToClipboard(copyValue);
            copyBtn.setIcon(AllIcons.Actions.Checked);
            Timer timer = new Timer(1500, ev -> copyBtn.setIcon(AllIcons.Actions.Copy));
            timer.setRepeats(false);
            timer.start();
        });
        row.add(copyBtn, BorderLayout.EAST);

        return row;
    }

    private JPanel createActionPanel(SessionMeta session) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        panel.setBorder(JBUI.Borders.emptyTop(4));

        if (session.getResumeCommand() != null && !session.getResumeCommand().isBlank()) {
            JButton continueBtn = new JButton(I18n.t("session.button.continueConversation"));
            continueBtn.setIcon(AllIcons.Actions.Execute);
            continueBtn.setToolTipText(session.getResumeCommand());
            continueBtn.addActionListener(e -> onContinueConversation(session));
            panel.add(continueBtn);
        }

        JButton deleteBtn = new JButton(I18n.t("session.button.delete"));
        deleteBtn.setIcon(AllIcons.General.Remove);
        boolean deleteSupported = SessionScannerService.getInstance().supportsDelete(session);
        deleteBtn.setEnabled(deleteSupported);
        deleteBtn.setToolTipText(deleteSupported
                ? I18n.t("session.tooltip.delete")
                : I18n.t("session.tooltip.deleteUnsupported"));
        deleteBtn.addActionListener(e -> onDeleteSession(session));
        panel.add(deleteBtn);

        return panel;
    }

    private void onBatchDelete() {
        if (selectedCliType == null) {
            Messages.showWarningDialog(
                    I18n.t("providerDialog.validate.cliTypeRequired"),
                    I18n.t("session.button.batchDelete"));
            return;
        }
        BatchDeleteSessionsDialog dialog = new BatchDeleteSessionsDialog(
                selectedCliType,
                allSessions,
                () -> refreshSessions(true));
        dialog.show();
    }

    private void onContinueConversation(SessionMeta session) {
        String resumeCommand = session.getResumeCommand();
        if (resumeCommand == null || resumeCommand.isBlank()) {
            return;
        }

        try {
            TerminalSessionService.executeCommand(
                    project,
                    resolveWorkingDirectory(session),
                    I18n.t("session.terminal.continueTabName"),
                    resumeCommand);
        } catch (RuntimeException ex) {
            Messages.showErrorDialog(
                    I18n.t("session.dialog.continueFailed", ex.getMessage()),
                    I18n.t("provider.dialog.error"));
        }
    }

    private String resolveWorkingDirectory(SessionMeta session) {
        String projectDir = session.getProjectDir();
        if (projectDir != null && !projectDir.isBlank()) {
            return projectDir;
        }
        String basePath = project.getBasePath();
        return basePath != null && !basePath.isBlank()
                ? basePath
                : System.getProperty("user.home");
    }

    private void onDeleteSession(SessionMeta session) {
        SessionScannerService service = SessionScannerService.getInstance();
        if (!service.supportsDelete(session)) {
            Messages.showWarningDialog(
                    I18n.t("session.dialog.deleteUnsupported"),
                    I18n.t("session.dialog.deleteTitle"));
            return;
        }

        int result = Messages.showYesNoDialog(
                I18n.t("session.dialog.deleteConfirm", session.getDisplayTitle()),
                I18n.t("session.dialog.deleteTitle"),
                Messages.getQuestionIcon());
        if (result != Messages.YES) {
            return;
        }

        try {
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    service.deleteSession(session);
                    SwingUtilities.invokeLater(() -> refreshSessions(true));
                } catch (UnsupportedOperationException ex) {
                    SwingUtilities.invokeLater(() -> Messages.showWarningDialog(
                            I18n.t("session.dialog.deleteUnsupported"),
                            I18n.t("session.dialog.deleteTitle")));
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> Messages.showErrorDialog(
                            I18n.t("session.dialog.deleteFailed", ex.getMessage()),
                            I18n.t("provider.dialog.error")));
                }
            });
        } catch (Exception ex) {
            Messages.showErrorDialog(
                    I18n.t("session.dialog.deleteFailed", ex.getMessage()),
                    I18n.t("provider.dialog.error"));
        }
    }

    private void renderMessages(List<SessionMessage> messages) {
        messageContainer.removeAll();

        if (messages.isEmpty()) {
            JBLabel empty = new JBLabel(I18n.t("session.empty.noMessages"), SwingConstants.CENTER);
            empty.setForeground(UIUtil.getInactiveTextColor());
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            messageContainer.add(empty);
        } else {
            for (SessionMessage msg : messages) {
                messageContainer.add(Box.createVerticalStrut(6));
                messageContainer.add(createMessageCard(msg));
            }
            messageContainer.add(Box.createVerticalStrut(8));
        }

        messageContainer.revalidate();
        messageContainer.repaint();
    }

    private JPanel createMessageCard(SessionMessage message) {
        JPanel card = new JPanel(new BorderLayout(0, 4));
        boolean isUser = "user".equalsIgnoreCase(message.getRole());
        boolean isAssistant = "assistant".equalsIgnoreCase(message.getRole());

        Color bgColor;
        if (isUser) {
            bgColor = new Color(59, 130, 246, 15); // 蓝色半透明
        } else if (isAssistant) {
            bgColor = new Color(100, 116, 139, 12); // 灰蓝半透明
        } else {
            bgColor = new Color(245, 158, 11, 10); // 橙色半透明
        }

        card.setBackground(bgColor);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(128, 128, 128, 40), 1, true),
                JBUI.Borders.empty(8, 10)));
        card.setOpaque(true);
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        // 角色 + 时间标签
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setOpaque(false);

        JBLabel roleLabel = new JBLabel(getRoleLabel(message.getRole()));
        roleLabel.setFont(roleLabel.getFont().deriveFont(Font.BOLD, 11f));
        roleLabel.setForeground(getRoleColor(message.getRole()));
        headerPanel.add(roleLabel, BorderLayout.WEST);

        if (message.getTimestamp() != null) {
            JBLabel timeLabel = new JBLabel(formatTimestamp(message.getTimestamp()));
            timeLabel.setFont(timeLabel.getFont().deriveFont(10f));
            timeLabel.setForeground(UIUtil.getInactiveTextColor());
            headerPanel.add(timeLabel, BorderLayout.EAST);
        }

        card.add(headerPanel, BorderLayout.NORTH);

        // 消息内容（限制最大行数，超长截断）
        String content = message.getContent();
        if (content.length() > 2000) {
            content = content.substring(0, 2000) + I18n.t("session.content.truncated");
        }
        JTextArea textArea = new JTextArea(content);
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setOpaque(false);
        textArea.setFont(textArea.getFont().deriveFont(12f));
        textArea.setBorder(JBUI.Borders.empty());

        card.add(textArea, BorderLayout.CENTER);

        // 设置左右缩进模拟消息气泡效果
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (isUser) {
            wrapper.setBorder(JBUI.Borders.emptyLeft(40));
        } else if (isAssistant) {
            wrapper.setBorder(JBUI.Borders.emptyRight(40));
        }
        wrapper.add(card, BorderLayout.CENTER);
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, wrapper.getPreferredSize().height + 200));

        return wrapper;
    }

    // =====================================================================
    // 搜索与刷新
    // =====================================================================

    private void onSearchChanged(String query) {
        this.searchQuery = query == null ? "" : query.trim().toLowerCase();
        applyFilter();
    }

    private void applyFilter() {
        listModel.clear();
        for (SessionMeta session : allSessions) {
            if (matchesSearch(session)) {
                listModel.addElement(session);
            }
        }
    }

    private boolean matchesSearch(SessionMeta session) {
        // 先按 CLI 类型过滤
        if (selectedProvider != null && !selectedProvider.equals(session.getProviderId())) {
            return false;
        }
        // 再按搜索关键词过滤
        if (searchQuery.isEmpty())
            return true;
        String title = session.getDisplayTitle();
        if (title != null && title.toLowerCase().contains(searchQuery))
            return true;
        String dir = session.getProjectDir();
        if (dir != null && dir.toLowerCase().contains(searchQuery))
            return true;
        String summary = session.getSummary();
        if (summary != null && summary.toLowerCase().contains(searchQuery))
            return true;
        return false;
    }

    public void autoRefreshOnEntry() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRefreshCompletedAt;
        if (lastRefreshCompletedAt < 0 || elapsed >= AUTO_REFRESH_INTERVAL_MS) {
            refreshSessions(false);
        }
    }

    private void refreshSessions(boolean force) {
        if (!force) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefreshCompletedAt;
            if (lastRefreshCompletedAt >= 0 && elapsed < AUTO_REFRESH_INTERVAL_MS) {
                return;
            }
        }
        if (!refreshInProgress.compareAndSet(false, true)) {
            return;
        }

        // 清空并显示加载状态
        listModel.clear();
        detailPanel.removeAll();
        JBLabel loading = new JBLabel(I18n.t("session.loading.scanning"), SwingConstants.CENTER);
        loading.setForeground(UIUtil.getInactiveTextColor());
        detailPanel.add(loading, BorderLayout.CENTER);
        detailPanel.revalidate();
        detailPanel.repaint();

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                List<SessionMeta> sessions = SessionScannerService.getInstance().scanAllSessions();
                SwingUtilities.invokeLater(() -> {
                    try {
                        allSessions = sessions;
                        applyFilter();

                        detailPanel.removeAll();
                        if (sessions.isEmpty()) {
                            JBLabel noData = new JBLabel(
                                    I18n.t("session.empty.noSessions"),
                                    SwingConstants.CENTER);
                            detailPanel.add(noData, BorderLayout.CENTER);
                        } else {
                            detailPanel.add(emptyLabel, BorderLayout.CENTER);
                        }
                        detailPanel.revalidate();
                        detailPanel.repaint();
                        lastRefreshCompletedAt = System.currentTimeMillis();
                    } finally {
                        refreshInProgress.set(false);
                    }
                });
            } catch (RuntimeException ex) {
                SwingUtilities.invokeLater(() -> refreshInProgress.set(false));
                throw ex;
            }
        });
    }

    // =====================================================================
    // 工具方法
    // =====================================================================

    private void copyToClipboard(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
    }

    private String getCliDisplayName(String providerId) {
        CliType cliType = CliType.fromId(providerId);
        return cliType == null ? providerId : cliType.getDisplayName();
    }

    private String getRoleLabel(String role) {
        if (role == null) {
            return I18n.t("session.role.unknown");
        }
        return switch (role.toLowerCase()) {
            case "assistant" -> I18n.t("session.role.assistant");
            case "user" -> I18n.t("session.role.user");
            case "system" -> I18n.t("session.role.system");
            case "tool" -> I18n.t("session.role.tool");
            default -> role;
        };
    }

    private Color getRoleColor(String role) {
        if (role == null)
            return UIUtil.getLabelForeground();
        return switch (role.toLowerCase()) {
            case "assistant" -> new Color(59, 130, 246);
            case "user" -> new Color(16, 185, 129);
            case "system" -> new Color(245, 158, 11);
            case "tool" -> new Color(139, 92, 246);
            default -> UIUtil.getLabelForeground();
        };
    }

    private String truncateId(String id) {
        if (id == null)
            return "";
        return id.length() > 12 ? id.substring(0, 12) + "..." : id;
    }

    private String formatTimestamp(long millis) {
        return DateTimeFormatter.ofPattern("HH:mm:ss")
                .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    private String formatRelativeTime(Long millis) {
        if (millis == null)
            return I18n.t("session.time.unknown");
        long diff = System.currentTimeMillis() - millis;
        long minutes = diff / 60_000;
        long hours = diff / 3_600_000;
        long days = diff / 86_400_000;

        if (minutes < 1)
            return I18n.t("session.time.justNow");
        if (minutes < 60)
            return I18n.t("session.time.minutesAgo", minutes);
        if (hours < 24)
            return I18n.t("session.time.hoursAgo", hours);
        if (days < 7)
            return I18n.t("session.time.daysAgo", days);
        return DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    private String formatTimeRange(Long createdAt, Long lastActiveAt) {
        StringBuilder sb = new StringBuilder();
        if (createdAt != null) {
            sb.append(I18n.t("session.time.created", formatRelativeTime(createdAt)));
        }
        if (lastActiveAt != null && !lastActiveAt.equals(createdAt)) {
            if (!sb.isEmpty())
                sb.append("  ·  ");
            sb.append(I18n.t("session.time.lastActive", formatRelativeTime(lastActiveAt)));
        }
        return sb.toString();
    }

    // =====================================================================
    // 列表单元格渲染器
    // =====================================================================

    private class SessionListCellRenderer extends JPanel implements ListCellRenderer<SessionMeta> {
        private final JBLabel titleLabel = new JBLabel();
        private final JBLabel providerLabel = new JBLabel();
        private final JBLabel timeLabel = new JBLabel();

        SessionListCellRenderer() {
            setLayout(new BorderLayout(4, 2));
            setBorder(JBUI.Borders.empty(6, 8));

            JPanel topLine = new JPanel(new BorderLayout(4, 0));
            topLine.setOpaque(false);

            providerLabel.setFont(providerLabel.getFont().deriveFont(10f));
            topLine.add(providerLabel, BorderLayout.WEST);

            titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 12f));
            topLine.add(titleLabel, BorderLayout.CENTER);

            add(topLine, BorderLayout.NORTH);

            timeLabel.setFont(timeLabel.getFont().deriveFont(10f));
            timeLabel.setForeground(UIUtil.getInactiveTextColor());
            add(timeLabel, BorderLayout.SOUTH);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends SessionMeta> list,
                SessionMeta value, int index,
                boolean isSelected, boolean cellHasFocus) {
            String cliTag = "[" + getCliDisplayName(value.getProviderId()) + "]";
            providerLabel.setText(cliTag);
            providerLabel.setForeground(getRoleColor("assistant"));

            titleLabel.setText(value.getDisplayTitle());

            Long ts = value.getLastActiveAt() != null ? value.getLastActiveAt() : value.getCreatedAt();
            timeLabel.setText(formatRelativeTime(ts));

            if (isSelected) {
                setBackground(UIUtil.getListSelectionBackground(true));
                titleLabel.setForeground(UIUtil.getListSelectionForeground(true));
            } else {
                setBackground(UIUtil.getListBackground());
                titleLabel.setForeground(UIUtil.getListForeground());
            }

            return this;
        }
    }
}
