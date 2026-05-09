package com.github.mostbean.codingswitch.service;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * 保存下一次终端启动需要注入的一次性环境变量。
 */
@Service(Service.Level.APP)
public final class TemporaryTerminalEnvironmentService {

    private static final long ENTRY_TTL_MILLIS = 10_000L;

    private PendingEnvironment pendingEnvironment;

    public static TemporaryTerminalEnvironmentService getInstance() {
        return ApplicationManager.getApplication().getService(TemporaryTerminalEnvironmentService.class);
    }

    public synchronized void prepare(
        @NotNull Project project,
        @NotNull String workingDirectory,
        @NotNull Map<String, String> environment
    ) {
        if (environment.isEmpty()) {
            pendingEnvironment = null;
            return;
        }
        pendingEnvironment = new PendingEnvironment(
            project.getLocationHash(),
            normalizePath(workingDirectory),
            System.currentTimeMillis() + ENTRY_TTL_MILLIS,
            new LinkedHashMap<>(environment)
        );
    }

    public synchronized @NotNull Map<String, String> consume(
        Project project,
        String workingDirectory
    ) {
        if (pendingEnvironment == null) {
            return Collections.emptyMap();
        }
        if (pendingEnvironment.expiresAtMillis < System.currentTimeMillis()) {
            pendingEnvironment = null;
            return Collections.emptyMap();
        }
        if (!pendingEnvironment.matches(project, workingDirectory)) {
            return Collections.emptyMap();
        }

        Map<String, String> environment = new LinkedHashMap<>(pendingEnvironment.environment);
        pendingEnvironment = null;
        return environment;
    }

    public synchronized void clear(Project project, String workingDirectory) {
        if (pendingEnvironment != null && pendingEnvironment.matches(project, workingDirectory)) {
            pendingEnvironment = null;
        }
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        try {
            return Path.of(path).toAbsolutePath().normalize().toString();
        } catch (RuntimeException ignored) {
            return path.trim();
        }
    }

    private record PendingEnvironment(
        String projectLocationHash,
        String workingDirectory,
        long expiresAtMillis,
        Map<String, String> environment
    ) {
        private boolean matches(Project project, String currentWorkingDirectory) {
            return project != null && projectLocationHash.equals(project.getLocationHash());
        }
    }
}
