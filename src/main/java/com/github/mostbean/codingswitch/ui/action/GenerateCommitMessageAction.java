package com.github.mostbean.codingswitch.ui.action;

import com.github.mostbean.codingswitch.service.AiCommitMessageService;
import com.github.mostbean.codingswitch.service.AiFeatureSettings;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataKey;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.util.Key;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;

public class GenerateCommitMessageAction extends DumbAwareAction {

    private static final DataKey<Object> COMMIT_MESSAGE_PANEL_KEY = DataKey.create("Vcs.CommitMessage.Panel");
    private static final DataKey<Object> CHANGES_SUPPLIER_KEY = DataKey.create("Vcs.CommitMessage.CompletionContext");
    private static final DataKey<Object> COMMIT_WORKFLOW_UI_KEY = DataKey.create("Vcs.CommitWorkflowUI");
    private static final Key<AtomicBoolean> COMMIT_GENERATION_STATE_KEY =
        Key.create("coding.switch.ai.commit.message.generating");

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        Object commitMessagePanel = e.getData(COMMIT_MESSAGE_PANEL_KEY);
        Object commitMessageControl = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL);
        Object commitWorkflowUi = e.getData(COMMIT_WORKFLOW_UI_KEY);
        Document commitMessageDocument = e.getData(VcsDataKeys.COMMIT_MESSAGE_DOCUMENT);
        Object changesSupplier = e.getData(CHANGES_SUPPLIER_KEY);
        Editor fallbackEditor = e.getData(CommonDataKeys.EDITOR);
        List<Change> changes = resolveChanges(e, changesSupplier);
        List<?> unversionedFiles = toList(resolveIncludedUnversionedFilesFromWorkflow(e));
        if (changes.isEmpty() && unversionedFiles.isEmpty()) {
            showNotification(project, "请先在提交窗口选择要生成提交信息的文件。", NotificationType.WARNING);
            return;
        }
        if (!tryStartCommitGeneration(project)) {
            showNotification(project, "正在生成 Git 提交信息，请稍候。", NotificationType.INFORMATION);
            return;
        }

        new Task.Backgroundable(project, "生成 Git 提交信息", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                boolean clearOnExit = true;
                try {
                    AtomicReference<String> latestPartial = new AtomicReference<>("");
                    AtomicBoolean updateScheduled = new AtomicBoolean(false);
                    Consumer<String> partialConsumer = partial -> {
                        if (partial == null || partial.isBlank()) {
                            return;
                        }
                        latestPartial.set(partial);
                        if (!updateScheduled.compareAndSet(false, true)) {
                            return;
                        }
                        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(
                            () -> {
                                updateScheduled.set(false);
                                String value = latestPartial.get();
                                if (value.isBlank() || !isCommitGenerationInProgress(project)) {
                                    return;
                                }
                                applyCommitMessage(
                                    project,
                                    commitMessagePanel,
                                    commitMessageControl,
                                    commitWorkflowUi,
                                    commitMessageDocument,
                                    fallbackEditor,
                                    value
                                );
                            }
                        );
                    };
                    Optional<String> message = AiCommitMessageService.getInstance()
                        .generateStreaming(changes, unversionedFiles, partialConsumer);
                    if (message.isEmpty()) {
                        showNotification(project, "未生成提交信息，请检查当前变更后重试。", NotificationType.WARNING);
                        return;
                    }
                    clearOnExit = false;
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(
                        () -> {
                            try {
                                applyCommitMessage(
                                    project,
                                    commitMessagePanel,
                                    commitMessageControl,
                                    commitWorkflowUi,
                                    commitMessageDocument,
                                    fallbackEditor,
                                    message.get()
                                );
                            } finally {
                                finishCommitGeneration(project);
                            }
                        }
                    );
                } catch (Exception ex) {
                    showNotification(project, "生成提交信息失败: " + ex.getMessage(), NotificationType.ERROR);
                } finally {
                    if (clearOnExit) {
                        finishCommitGeneration(project);
                    }
                }
            }
        }.queue();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        boolean visible = AiFeatureSettings.getInstance().isGitCommitMessageEnabled() && project != null;
        e.getPresentation().setVisible(visible);
        e.getPresentation().setEnabled(visible && !isCommitGenerationInProgress(project) && hasSelectedChanges(e));
    }

    private boolean hasSelectedChanges(AnActionEvent e) {
        Object changesSupplier = e.getData(CHANGES_SUPPLIER_KEY);
        return !resolveChanges(e, changesSupplier).isEmpty()
            || resolveIncludedUnversionedFilesFromWorkflow(e).iterator().hasNext();
    }

    private boolean tryStartCommitGeneration(Project project) {
        return commitGenerationState(project).compareAndSet(false, true);
    }

    private void finishCommitGeneration(Project project) {
        commitGenerationState(project).set(false);
    }

    private boolean isCommitGenerationInProgress(Project project) {
        return project != null && commitGenerationState(project).get();
    }

    private AtomicBoolean commitGenerationState(Project project) {
        AtomicBoolean state = project.getUserData(COMMIT_GENERATION_STATE_KEY);
        if (state != null) {
            return state;
        }
        synchronized (project) {
            state = project.getUserData(COMMIT_GENERATION_STATE_KEY);
            if (state == null) {
                state = new AtomicBoolean(false);
                project.putUserData(COMMIT_GENERATION_STATE_KEY, state);
            }
            return state;
        }
    }

    private List<Change> resolveChanges(AnActionEvent e, Object changesSupplier) {
        List<Change> selected = new ArrayList<>();
        addChanges(selected, resolveIncludedChangesFromWorkflow(e));
        if (!selected.isEmpty()) {
            return selected;
        }
        selected = resolveSelectedChanges(e);
        if (!selected.isEmpty()) {
            return selected;
        }
        if (changesSupplier instanceof Supplier<?> supplier) {
            try {
                Object value = supplier.get();
                if (value instanceof Iterable<?> iterable) {
                    addChanges(selected, iterable);
                }
            } catch (RuntimeException ignored) {
                // 提交窗口未提供上下文时保持禁用。
            }
        }
        return selected;
    }

    private Iterable<?> resolveIncludedChangesFromWorkflow(AnActionEvent e) {
        return resolveIterableFromWorkflow(e, "getIncludedChanges");
    }

    private Iterable<?> resolveIncludedUnversionedFilesFromWorkflow(AnActionEvent e) {
        return resolveIterableFromWorkflow(e, "getIncludedUnversionedFiles");
    }

    private Iterable<?> resolveIterableFromWorkflow(AnActionEvent e, String methodName) {
        Object workflowUi = e.getData(COMMIT_WORKFLOW_UI_KEY);
        if (workflowUi == null) {
            return List.of();
        }
        try {
            Object value = workflowUi.getClass().getMethod(methodName).invoke(workflowUi);
            return value instanceof Iterable<?> iterable ? iterable : List.of();
        } catch (ReflectiveOperationException ignored) {
            return List.of();
        }
    }

    private List<?> toList(Iterable<?> values) {
        List<Object> list = new ArrayList<>();
        for (Object value : values) {
            if (value != null) {
                list.add(value);
            }
        }
        return list;
    }

    private List<Change> resolveSelectedChanges(AnActionEvent e) {
        List<Change> selected = new ArrayList<>();
        addChange(selected, e.getData(VcsDataKeys.CURRENT_CHANGE));
        addChanges(selected, e.getData(VcsDataKeys.SELECTED_CHANGES));
        addChanges(selected, e.getData(VcsDataKeys.SELECTED_CHANGES_IN_DETAILS));
        addChanges(selected, e.getData(VcsDataKeys.CHANGE_LEAD_SELECTION));
        addChanges(selected, e.getData(VcsDataKeys.CHANGES));
        return selected;
    }

    private void addChange(List<Change> target, Change change) {
        if (change != null && !target.contains(change)) {
            target.add(change);
        }
    }

    private void addChanges(List<Change> target, Change[] changes) {
        if (changes == null) {
            return;
        }
        for (Change change : Arrays.asList(changes)) {
            addChange(target, change);
        }
    }

    private void addChanges(List<Change> target, Iterable<?> values) {
        for (Object value : values) {
            if (value instanceof Change change) {
                addChange(target, change);
            } else if (value instanceof Collection<?> collection) {
                addChanges(target, collection);
            }
        }
    }

    private void applyCommitMessage(
        Project project,
        Object commitMessagePanel,
        Object commitMessageControl,
        Object commitWorkflowUi,
        Document commitMessageDocument,
        Editor fallbackEditor,
        String message
    ) {
        if (setTextByMethod(commitMessageControl, "setCommitMessage", message)
            || setTextByMethod(commitMessagePanel, "setCommitMessage", message)
            || setTextByMethod(commitMessagePanel, "setText", message)
            || setTextOnWorkflowUi(commitWorkflowUi, message)) {
            return;
        }
        if (commitMessageDocument != null) {
            WriteCommandAction.runWriteCommandAction(
                project,
                () -> commitMessageDocument.setText(message)
            );
            return;
        }
        if (fallbackEditor == null) {
            showNotification(project, "无法定位提交信息输入框，请重新打开提交窗口后重试。", NotificationType.WARNING);
            return;
        }
        WriteCommandAction.runWriteCommandAction(
            project,
            () -> fallbackEditor.getDocument().setText(message)
        );
    }

    private boolean setTextOnWorkflowUi(Object commitWorkflowUi, String message) {
        if (commitWorkflowUi == null) {
            return false;
        }
        try {
            Object messageUi = commitWorkflowUi.getClass().getMethod("getCommitMessageUi").invoke(commitWorkflowUi);
            return setTextByMethod(messageUi, "setText", message);
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private boolean setTextByMethod(Object target, String methodName, String message) {
        if (target == null) {
            return false;
        }
        try {
            target.getClass()
                .getMethod(methodName, String.class)
                .invoke(target, message);
            return true;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private void showNotification(Project project, String message, NotificationType type) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Coding Switch")
            .createNotification(message, type)
            .notify(project);
    }
}
