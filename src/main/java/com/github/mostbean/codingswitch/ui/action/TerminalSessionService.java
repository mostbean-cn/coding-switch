package com.github.mostbean.codingswitch.ui.action;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.terminal.ui.TerminalWidget;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.terminal.TerminalToolWindowManager;

/**
 * 兼容不同 IntelliJ 平台版本的终端会话创建入口。
 */
public final class TerminalSessionService {

    private static final Logger LOG = Logger.getInstance(TerminalSessionService.class);

    private TerminalSessionService() {}

    public static @NotNull TerminalWidget createTerminalSession(
        @NotNull Project project,
        @NotNull String workingDirectory,
        @NotNull String tabName
    ) {
        TerminalToolWindowManager terminalManager = TerminalToolWindowManager.getInstance(project);
        try {
            return invokeCreateNewSession(terminalManager, workingDirectory, tabName);
        } catch (NoSuchMethodException ignored) {
            return invokeLegacyCreateShellWidget(terminalManager, workingDirectory, tabName);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("无法访问 IDE 终端创建接口", error);
        } catch (InvocationTargetException error) {
            throw rethrowInvocationTarget(error);
        }
    }

    public static void executeCommand(
        @NotNull Project project,
        @NotNull String workingDirectory,
        @NotNull String tabName,
        @NotNull String command
    ) {
        TerminalToolWindowManager terminalManager = TerminalToolWindowManager.getInstance(project);
        TerminalWidget terminalWidget = createTerminalSession(project, workingDirectory, tabName);
        terminalWidget.sendCommandToExecute(command);

        ToolWindow toolWindow = terminalManager.getToolWindow();
        if (toolWindow != null) {
            toolWindow.activate(terminalWidget::requestFocus, true, true);
        } else {
            terminalWidget.requestFocus();
        }
    }

    private static @NotNull TerminalWidget invokeCreateNewSession(
        @NotNull TerminalToolWindowManager terminalManager,
        @NotNull String workingDirectory,
        @NotNull String tabName
    ) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        Method method = terminalManager.getClass().getMethod(
            "createNewSession",
            String.class,
            String.class,
            java.util.List.class,
            boolean.class,
            boolean.class
        );
        return castTerminalWidget(method.invoke(terminalManager, workingDirectory, tabName, null, true, true));
    }

    private static @NotNull TerminalWidget invokeLegacyCreateShellWidget(
        @NotNull TerminalToolWindowManager terminalManager,
        @NotNull String workingDirectory,
        @NotNull String tabName
    ) {
        try {
            Method legacyMethod = terminalManager.getClass().getMethod(
                "createShellWidget",
                String.class,
                String.class,
                boolean.class,
                boolean.class
            );
            return castTerminalWidget(legacyMethod.invoke(terminalManager, workingDirectory, tabName, true, true));
        } catch (NoSuchMethodException error) {
            throw new IllegalStateException("当前 IDE 不支持创建内置终端会话", error);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("无法访问 IDE 终端创建接口", error);
        } catch (InvocationTargetException error) {
            throw rethrowInvocationTarget(error);
        }
    }

    private static @NotNull TerminalWidget castTerminalWidget(Object value) {
        if (value instanceof TerminalWidget terminalWidget) {
            return terminalWidget;
        }
        throw new IllegalStateException("IDE 返回了未知的终端会话类型");
    }

    private static @NotNull RuntimeException rethrowInvocationTarget(
        @NotNull InvocationTargetException error
    ) {
        Throwable target = error.getTargetException();
        if (target instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        LOG.warn("创建 IDE 终端会话失败", target);
        return new IllegalStateException("创建 IDE 终端会话失败", target);
    }
}
