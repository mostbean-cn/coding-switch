package com.github.mostbean.codingswitch.ui.dialog;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.model.SessionMessage;
import com.github.mostbean.codingswitch.model.SessionMeta;
import com.github.mostbean.codingswitch.service.I18n;
import com.github.mostbean.codingswitch.service.SessionScannerService;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 当前 CLI 会话的批量删除对话框。
 */
public class BatchDeleteSessionsDialog extends DialogWrapper {

    private static final int PREVIEW_CONTENT_LIMIT = 1600;

    private final CliType cliType;
    private final Runnable refreshCallback;
    private final SessionTableModel tableModel;
    private final JBTable sessionTable;
    private final JPanel previewContainer = new JPanel();
    private final SearchTextField searchField = new SearchTextField();
    private final JCheckBox selectVisibleHeaderCheckbox = new JCheckBox();

    private List<SessionMeta> allSessions;
    private String searchQuery = "";
    private SessionDateFilter dateFilter = SessionDateFilter.all();

    public BatchDeleteSessionsDialog(
        @NotNull CliType cliType,
        @NotNull List<SessionMeta> sessions,
        @NotNull Runnable refreshCallback
    ) {
        super(true);
        this.cliType = cliType;
        this.refreshCallback = refreshCallback;
        this.allSessions = sessions.stream()
            .filter(session -> cliType.getId().equals(session.getProviderId()))
            .sorted(Comparator.comparingLong(SessionMeta::getEffectiveTimestamp).reversed())
            .collect(Collectors.toCollection(ArrayList::new));
        this.tableModel = new SessionTableModel();
        this.sessionTable = new JBTable(tableModel);

        setTitle(I18n.t("session.batchDelete.title", cliType.getDisplayName()));
        setOKButtonText(I18n.t("session.batchDelete.deleteSelected"));
        init();
        updateVisibleRows();
        updateOkActionState();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setPreferredSize(new Dimension(JBUI.scale(920), JBUI.scale(560)));
        root.add(createFilterPanel(), BorderLayout.NORTH);
        root.add(createContentPanel(), BorderLayout.CENTER);
        return root;
    }

    private JComponent createFilterPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton sevenDaysButton = new JButton(I18n.t("session.batchDelete.before7Days"));
        sevenDaysButton.addActionListener(e -> selectBeforeDays(7));
        buttonRow.add(sevenDaysButton);

        JButton thirtyDaysButton = new JButton(I18n.t("session.batchDelete.before30Days"));
        thirtyDaysButton.addActionListener(e -> selectBeforeDays(30));
        buttonRow.add(thirtyDaysButton);

        JButton rangeButton = new JButton(I18n.t("session.batchDelete.dateRange"));
        rangeButton.addActionListener(e -> selectDateRange());
        buttonRow.add(rangeButton);

        JButton allButton = new JButton(I18n.t("session.batchDelete.allSessions"));
        allButton.addActionListener(e -> {
            dateFilter = SessionDateFilter.all();
            updateVisibleRows();
            tableModel.clearSelection();
            updateOkActionState();
            repaintHeader();
        });
        buttonRow.add(allButton);

        JButton clearButton = new JButton(I18n.t("session.batchDelete.clearSelection"));
        clearButton.addActionListener(e -> {
            tableModel.clearSelection();
            updateOkActionState();
            repaintHeader();
        });
        buttonRow.add(clearButton);
        panel.add(buttonRow);

        JPanel searchRow = new JPanel(new BorderLayout());
        searchRow.setBorder(JBUI.Borders.emptyTop(8));
        searchField.addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onSearchChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onSearchChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onSearchChanged();
            }
        });
        searchRow.add(searchField, BorderLayout.CENTER);
        panel.add(searchRow);
        return panel;
    }

    private JComponent createContentPanel() {
        JPanel content = new JPanel(new BorderLayout(8, 0));
        content.add(createTablePanel(), BorderLayout.CENTER);
        content.add(createPreviewPanel(), BorderLayout.EAST);
        return content;
    }

    private JComponent createTablePanel() {
        sessionTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sessionTable.setRowHeight(JBUI.scale(28));
        sessionTable.getEmptyText().setText(I18n.t("session.batchDelete.empty"));
        sessionTable.getColumnModel().getColumn(0).setMaxWidth(JBUI.scale(42));
        sessionTable.getColumnModel().getColumn(0).setHeaderRenderer(createSelectVisibleHeaderRenderer());
        sessionTable.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(160));
        sessionTable.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(110));
        sessionTable.getColumnModel().getColumn(3).setPreferredWidth(JBUI.scale(240));
        sessionTable.getTableHeader().setReorderingAllowed(false);
        sessionTable.getTableHeader().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int column = sessionTable.getTableHeader().columnAtPoint(e.getPoint());
                if (column == 0) {
                    tableModel.setVisibleSelection(!tableModel.areAllVisibleSelected());
                    updateOkActionState();
                    repaintHeader();
                }
            }
        });
        sessionTable.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public java.awt.Component getTableCellRendererComponent(
                JTable table,
                Object value,
                boolean isSelected,
                boolean hasFocus,
                int row,
                int column
            ) {
                java.awt.Component component = super.getTableCellRendererComponent(
                    table,
                    value,
                    isSelected,
                    hasFocus,
                    row,
                    column
                );
                setHorizontalAlignment(SwingConstants.CENTER);
                return component;
            }
        });
        sessionTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = sessionTable.getSelectedRow();
                if (row >= 0) {
                    showPreview(tableModel.getSessionAt(sessionTable.convertRowIndexToModel(row)));
                }
            }
        });
        return new JBScrollPane(sessionTable);
    }

    private TableCellRenderer createSelectVisibleHeaderRenderer() {
        selectVisibleHeaderCheckbox.setHorizontalAlignment(SwingConstants.CENTER);
        selectVisibleHeaderCheckbox.setOpaque(true);
        return (table, value, isSelected, hasFocus, row, column) -> {
            selectVisibleHeaderCheckbox.setSelected(tableModel.areAllVisibleSelected());
            selectVisibleHeaderCheckbox.setBackground(table.getTableHeader().getBackground());
            selectVisibleHeaderCheckbox.setForeground(table.getTableHeader().getForeground());
            selectVisibleHeaderCheckbox.setBorder(BorderFactory.createEmptyBorder());
            return selectVisibleHeaderCheckbox;
        };
    }

    private JComponent createPreviewPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(JBUI.scale(360), 0));
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(I18n.t("session.batchDelete.preview")),
            JBUI.Borders.empty(6)
        ));
        previewContainer.setLayout(new BoxLayout(previewContainer, BoxLayout.Y_AXIS));
        showPreviewPlaceholder(I18n.t("session.batchDelete.previewHint"));
        JBScrollPane scrollPane = new JBScrollPane(previewContainer);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    private void onSearchChanged() {
        searchQuery = searchField.getText() == null
            ? ""
            : searchField.getText().trim().toLowerCase();
        updateVisibleRows();
    }

    private void updateVisibleRows() {
        List<SessionMeta> visible = allSessions.stream()
            .filter(session -> dateFilter.matches(effectiveTime(session)))
            .filter(this::matchesSearch)
            .toList();
        tableModel.setVisibleSessions(visible);
        repaintHeader();
    }

    private boolean matchesSearch(SessionMeta session) {
        if (searchQuery.isBlank()) {
            return true;
        }
        return containsIgnoreCase(session.getDisplayTitle())
            || containsIgnoreCase(session.getProjectDir())
            || containsIgnoreCase(session.getSummary());
    }

    private boolean containsIgnoreCase(String text) {
        return text != null && text.toLowerCase().contains(searchQuery);
    }

    private void selectBeforeDays(int days) {
        long cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L;
        applyDateFilterAndSelect(SessionDateFilter.before(cutoff));
    }

    private void selectDateRange() {
        DateRangeDialog dialog = new DateRangeDialog();
        if (dialog.showAndGet()) {
            applyDateFilterAndSelect(SessionDateFilter.between(dialog.getStartMillis(), dialog.getEndMillis()));
        }
    }

    private void applyDateFilterAndSelect(SessionDateFilter filter) {
        dateFilter = filter;
        updateVisibleRows();
        tableModel.replaceSelection(tableModel.getVisibleSessions());
        updateOkActionState();
        repaintHeader();
    }

    private void repaintHeader() {
        if (sessionTable.getTableHeader() != null) {
            sessionTable.getTableHeader().repaint();
        }
    }

    private Long effectiveTime(SessionMeta session) {
        if (session.getLastActiveAt() != null) {
            return session.getLastActiveAt();
        }
        return session.getCreatedAt();
    }

    private void showPreview(SessionMeta session) {
        previewContainer.removeAll();
        addPreviewMeta(session);
        JBLabel loadingLabel = new JBLabel(I18n.t("session.loading.messages"), SwingConstants.CENTER);
        loadingLabel.setForeground(UIUtil.getInactiveTextColor());
        loadingLabel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        previewContainer.add(Box.createVerticalStrut(8));
        previewContainer.add(loadingLabel);
        previewContainer.revalidate();
        previewContainer.repaint();

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<SessionMessage> messages = SessionScannerService.getInstance()
                .loadMessages(session.getProviderId(), session.getSourcePath());
            SwingUtilities.invokeLater(() -> renderPreview(session, messages));
        });
    }

    private void renderPreview(SessionMeta session, List<SessionMessage> messages) {
        previewContainer.removeAll();
        addPreviewMeta(session);
        if (messages.isEmpty()) {
            addPreviewPlaceholder(I18n.t("session.empty.noMessages"));
        } else {
            for (SessionMessage message : messages) {
                previewContainer.add(Box.createVerticalStrut(6));
                previewContainer.add(createPreviewMessage(message));
            }
        }
        previewContainer.revalidate();
        previewContainer.repaint();
    }

    private void addPreviewMeta(SessionMeta session) {
        JBLabel title = new JBLabel(session.getDisplayTitle());
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        title.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        previewContainer.add(title);

        JBLabel time = new JBLabel(formatDateTime(effectiveTime(session)));
        time.setForeground(UIUtil.getInactiveTextColor());
        time.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        previewContainer.add(time);

        JSeparator separator = new JSeparator();
        separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        separator.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        previewContainer.add(Box.createVerticalStrut(6));
        previewContainer.add(separator);
    }

    private JComponent createPreviewMessage(SessionMessage message) {
        JPanel panel = new JPanel(new BorderLayout(0, 3));
        panel.setOpaque(false);
        panel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

        JBLabel roleLabel = new JBLabel(message.getRole() == null ? I18n.t("session.role.unknown") : message.getRole());
        roleLabel.setFont(roleLabel.getFont().deriveFont(Font.BOLD, 11f));
        panel.add(roleLabel, BorderLayout.NORTH);

        String content = message.getContent() == null ? "" : message.getContent();
        if (content.length() > PREVIEW_CONTENT_LIMIT) {
            content = content.substring(0, PREVIEW_CONTENT_LIMIT) + I18n.t("session.content.truncated");
        }
        JTextArea contentArea = new JTextArea(content);
        contentArea.setEditable(false);
        contentArea.setLineWrap(true);
        contentArea.setWrapStyleWord(true);
        contentArea.setOpaque(false);
        contentArea.setBorder(JBUI.Borders.empty());
        contentArea.setFont(contentArea.getFont().deriveFont(12f));
        panel.add(contentArea, BorderLayout.CENTER);
        return panel;
    }

    private void showPreviewPlaceholder(String text) {
        previewContainer.removeAll();
        addPreviewPlaceholder(text);
        previewContainer.revalidate();
        previewContainer.repaint();
    }

    private void addPreviewPlaceholder(String text) {
        JBLabel label = new JBLabel(text, SwingConstants.CENTER);
        label.setForeground(UIUtil.getInactiveTextColor());
        label.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        previewContainer.add(label);
    }

    private void updateOkActionState() {
        setOKActionEnabled(tableModel.getSelectedSessions().size() > 0);
    }

    @Override
    protected void doOKAction() {
        List<SessionMeta> selected = tableModel.getSelectedSessions();
        if (selected.isEmpty()) {
            Messages.showWarningDialog(
                I18n.t("session.batchDelete.noSelection"),
                I18n.t("session.batchDelete.title", cliType.getDisplayName())
            );
            return;
        }

        int confirm = Messages.showYesNoDialog(
            I18n.t("session.batchDelete.confirm", selected.size(), cliType.getDisplayName()),
            I18n.t("session.dialog.deleteTitle"),
            Messages.getWarningIcon()
        );
        if (confirm != Messages.YES) {
            return;
        }

        setOKActionEnabled(false);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            DeleteReport report = deleteSessions(selected);
            SwingUtilities.invokeLater(() -> {
                refreshCallback.run();
                showDeleteReport(report);
                close(OK_EXIT_CODE);
            });
        });
    }

    private DeleteReport deleteSessions(List<SessionMeta> sessions) {
        SessionScannerService service = SessionScannerService.getInstance();
        int success = 0;
        List<String> failures = new ArrayList<>();
        for (SessionMeta session : sessions) {
            try {
                service.deleteSession(session);
                success++;
            } catch (Exception ex) {
                failures.add(session.getDisplayTitle() + ": " + Objects.toString(ex.getMessage(), ex.getClass().getSimpleName()));
            }
        }
        return new DeleteReport(success, failures);
    }

    private void showDeleteReport(DeleteReport report) {
        if (report.failures().isEmpty()) {
            Messages.showInfoMessage(
                I18n.t("session.batchDelete.done", report.success()),
                I18n.t("session.dialog.deleteTitle")
            );
            return;
        }
        String failureText = report.failures().stream()
            .limit(5)
            .collect(Collectors.joining("\n"));
        if (report.failures().size() > 5) {
            failureText += "\n" + I18n.t("session.batchDelete.moreFailures", report.failures().size() - 5);
        }
        Messages.showWarningDialog(
            I18n.t("session.batchDelete.partialDone", report.success(), report.failures().size(), failureText),
            I18n.t("session.dialog.deleteTitle")
        );
    }

    private String formatDateTime(Long millis) {
        if (millis == null) {
            return I18n.t("session.time.unknown");
        }
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    @Override
    protected Action[] createActions() {
        return new Action[] { getCancelAction(), getOKAction() };
    }

    private record DeleteReport(int success, List<String> failures) {}

    private record SessionDateFilter(@Nullable Long startMillis, @Nullable Long endMillis) {
        static SessionDateFilter all() {
            return new SessionDateFilter(null, null);
        }

        static SessionDateFilter before(long cutoffMillis) {
            return new SessionDateFilter(null, cutoffMillis - 1);
        }

        static SessionDateFilter between(long startMillis, long endMillis) {
            return new SessionDateFilter(startMillis, endMillis);
        }

        boolean matches(@Nullable Long millis) {
            if (startMillis == null && endMillis == null) {
                return true;
            }
            if (millis == null) {
                return false;
            }
            return (startMillis == null || millis >= startMillis)
                && (endMillis == null || millis <= endMillis);
        }
    }

    private final class DateRangeDialog extends DialogWrapper {
        private final DateSelectionPanel startPanel;
        private final DateSelectionPanel endPanel;

        private DateRangeDialog() {
            super(true);
            LocalDate today = LocalDate.now();
            this.startPanel = new DateSelectionPanel(I18n.t("session.batchDelete.startDate"), today.minusDays(30));
            this.endPanel = new DateSelectionPanel(I18n.t("session.batchDelete.endDate"), today);
            setTitle(I18n.t("session.batchDelete.dateRange"));
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new GridLayout(1, 2, 12, 0));
            panel.setPreferredSize(new Dimension(JBUI.scale(560), JBUI.scale(300)));
            panel.add(startPanel);
            panel.add(endPanel);
            return panel;
        }

        @Override
        protected void doOKAction() {
            if (endPanel.getSelectedDate().isBefore(startPanel.getSelectedDate())) {
                Messages.showWarningDialog(
                    I18n.t("session.batchDelete.invalidDateRange"),
                    I18n.t("session.batchDelete.dateRange")
                );
                return;
            }
            super.doOKAction();
        }

        private long getStartMillis() {
            return startPanel.getSelectedDate()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
        }

        private long getEndMillis() {
            return endPanel.getSelectedDate()
                .plusDays(1)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli() - 1;
        }
    }

    private final class DateSelectionPanel extends JPanel {
        private final JPanel daysPanel = new JPanel(new GridLayout(0, 7, 4, 4));
        private final JBLabel monthLabel = new JBLabel("", SwingConstants.CENTER);
        private LocalDate selectedDate;
        private YearMonth visibleMonth;

        private DateSelectionPanel(String title, LocalDate initialDate) {
            super(new BorderLayout(0, 8));
            this.selectedDate = initialDate;
            this.visibleMonth = YearMonth.from(initialDate);
            setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(title),
                JBUI.Borders.empty(6)
            ));

            JPanel header = new JPanel(new BorderLayout(4, 0));
            JButton previousButton = new JButton("<");
            previousButton.addActionListener(e -> {
                visibleMonth = visibleMonth.minusMonths(1);
                renderDays();
            });
            JButton nextButton = new JButton(">");
            nextButton.addActionListener(e -> {
                visibleMonth = visibleMonth.plusMonths(1);
                renderDays();
            });
            header.add(previousButton, BorderLayout.WEST);
            header.add(monthLabel, BorderLayout.CENTER);
            header.add(nextButton, BorderLayout.EAST);
            add(header, BorderLayout.NORTH);
            add(daysPanel, BorderLayout.CENTER);

            renderDays();
        }

        private LocalDate getSelectedDate() {
            return selectedDate;
        }

        private void renderDays() {
            monthLabel.setText(visibleMonth.format(DateTimeFormatter.ofPattern("yyyy-MM")));
            daysPanel.removeAll();
            for (String weekday : I18n.t("session.batchDelete.weekdays").split(",")) {
                JBLabel label = new JBLabel(weekday, SwingConstants.CENTER);
                label.setForeground(UIUtil.getInactiveTextColor());
                daysPanel.add(label);
            }

            LocalDate firstDay = visibleMonth.atDay(1);
            int leadingBlanks = firstDay.getDayOfWeek().getValue() - 1;
            for (int i = 0; i < leadingBlanks; i++) {
                daysPanel.add(Box.createGlue());
            }

            int daysInMonth = visibleMonth.lengthOfMonth();
            for (int day = 1; day <= daysInMonth; day++) {
                LocalDate date = visibleMonth.atDay(day);
                JButton dayButton = new JButton(String.valueOf(day));
                dayButton.setMargin(JBUI.emptyInsets());
                dayButton.setFocusPainted(false);
                dayButton.setOpaque(true);
                if (date.equals(selectedDate)) {
                    dayButton.setBackground(UIManager.getColor("Table.selectionBackground"));
                    dayButton.setForeground(UIManager.getColor("Table.selectionForeground"));
                }
                dayButton.addActionListener(e -> {
                    selectedDate = date;
                    visibleMonth = YearMonth.from(date);
                    renderDays();
                });
                daysPanel.add(dayButton);
            }

            daysPanel.revalidate();
            daysPanel.repaint();
        }
    }

    private final class SessionTableModel extends AbstractTableModel {
        private final String[] columns = {
            "",
            I18n.t("session.batchDelete.col.session"),
            I18n.t("session.batchDelete.col.lastActive"),
            I18n.t("session.batchDelete.col.project")
        };
        private final Map<String, Boolean> checkedByKey = new HashMap<>();
        private List<SessionMeta> visibleSessions = new ArrayList<>();

        void setVisibleSessions(List<SessionMeta> sessions) {
            this.visibleSessions = new ArrayList<>(sessions);
            fireTableDataChanged();
        }

        List<SessionMeta> getVisibleSessions() {
            return new ArrayList<>(visibleSessions);
        }

        SessionMeta getSessionAt(int row) {
            return visibleSessions.get(row);
        }

        void replaceSelection(List<SessionMeta> selectedSessions) {
            checkedByKey.clear();
            for (SessionMeta session : selectedSessions) {
                checkedByKey.put(sessionKey(session), true);
            }
            fireTableDataChanged();
        }

        void clearSelection() {
            checkedByKey.clear();
            fireTableDataChanged();
        }

        void setVisibleSelection(boolean selected) {
            for (SessionMeta session : visibleSessions) {
                String key = sessionKey(session);
                if (selected) {
                    checkedByKey.put(key, true);
                } else {
                    checkedByKey.remove(key);
                }
            }
            fireTableDataChanged();
        }

        boolean areAllVisibleSelected() {
            return !visibleSessions.isEmpty()
                && visibleSessions.stream()
                    .allMatch(session -> Boolean.TRUE.equals(checkedByKey.get(sessionKey(session))));
        }

        List<SessionMeta> getSelectedSessions() {
            return allSessions.stream()
                .filter(session -> Boolean.TRUE.equals(checkedByKey.get(sessionKey(session))))
                .toList();
        }

        @Override
        public int getRowCount() {
            return visibleSessions.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            SessionMeta session = visibleSessions.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> Boolean.TRUE.equals(checkedByKey.get(sessionKey(session)));
                case 1 -> session.getDisplayTitle();
                case 2 -> formatDateTime(effectiveTime(session));
                case 3 -> {
                    String projectDir = session.getProjectDir();
                    yield projectDir == null || projectDir.isBlank() ? Objects.toString(session.getSummary(), "") : projectDir;
                }
                default -> "";
            };
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (columnIndex != 0) {
                return;
            }
            SessionMeta session = visibleSessions.get(rowIndex);
            checkedByKey.put(sessionKey(session), Boolean.TRUE.equals(aValue));
            updateOkActionState();
            repaintHeader();
        }

        private String sessionKey(SessionMeta session) {
            if (session.getProviderId() != null && session.getSessionId() != null) {
                return session.getProviderId() + "#" + session.getSessionId();
            }
            return Objects.toString(session.getSourcePath(), String.valueOf(System.identityHashCode(session)));
        }
    }
}
