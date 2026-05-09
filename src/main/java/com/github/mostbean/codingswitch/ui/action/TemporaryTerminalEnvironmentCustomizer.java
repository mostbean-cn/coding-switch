package com.github.mostbean.codingswitch.ui.action;

import com.github.mostbean.codingswitch.service.TemporaryTerminalEnvironmentService;
import com.intellij.openapi.project.Project;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.terminal.LocalTerminalCustomizer;

/**
 * 在终端进程启动前注入一次性环境变量，避免把密钥写入命令行或本地配置文件。
 */
public class TemporaryTerminalEnvironmentCustomizer extends LocalTerminalCustomizer {

    @Override
    public String[] customizeCommandAndEnvironment(
        Project project,
        String workingDirectory,
        String[] command,
        Map<String, String> envs
    ) {
        mergePreparedEnvironment(project, workingDirectory, envs);
        return command;
    }

    @Override
    public String[] customizeCommandAndEnvironment(
        Project project,
        String[] command,
        Map<String, String> envs
    ) {
        mergePreparedEnvironment(project, null, envs);
        return command;
    }

    private void mergePreparedEnvironment(
        Project project,
        String workingDirectory,
        @NotNull Map<String, String> envs
    ) {
        Map<String, String> prepared = TemporaryTerminalEnvironmentService
            .getInstance()
            .consume(project, workingDirectory);
        if (!prepared.isEmpty()) {
            envs.putAll(prepared);
        }
    }
}
