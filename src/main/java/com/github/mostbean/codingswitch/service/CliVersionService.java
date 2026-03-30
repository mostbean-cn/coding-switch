package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.CliType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CLI 版本检测服务。
 */
@Service(Service.Level.APP)
public final class CliVersionService {

    private static final Logger LOG = Logger.getInstance(CliVersionService.class);
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+\\.\\d+[.\\d]*)");
    private static final long COMMAND_CHECK_TIMEOUT_SECONDS = 3;
    private static final long VERSION_TIMEOUT_SECONDS = 10;
    private static final long GEMINI_VERSION_TIMEOUT_SECONDS = 30;
    private static final long LATEST_TIMEOUT_SECONDS = 15;

    public enum VersionStatus {
        INSTALLED,
        NOT_INSTALLED,
        TIMEOUT,
        COMMAND_FAILED
    }

    public static final class VersionResult {
        private final VersionStatus status;
        private final String version;
        private final String detail;

        private VersionResult(VersionStatus status, String version, String detail) {
            this.status = status;
            this.version = version;
            this.detail = detail;
        }

        public static VersionResult installed(String version) {
            return new VersionResult(VersionStatus.INSTALLED, version, null);
        }

        public static VersionResult notInstalled() {
            return new VersionResult(VersionStatus.NOT_INSTALLED, null, null);
        }

        public static VersionResult timeout(String detail) {
            return new VersionResult(VersionStatus.TIMEOUT, null, detail);
        }

        public static VersionResult failed(String detail) {
            return new VersionResult(VersionStatus.COMMAND_FAILED, null, detail);
        }

        public VersionStatus status() {
            return status;
        }

        public String version() {
            return version;
        }

        public String detail() {
            return detail;
        }
    }

    public static CliVersionService getInstance() {
        return ApplicationManager.getApplication().getService(CliVersionService.class);
    }

    /**
     * 兼容旧调用：仅返回版本号。
     */
    public String getVersion(CliType cliType) {
        VersionResult result = getVersionResult(cliType);
        return result.status() == VersionStatus.INSTALLED ? result.version() : null;
    }

    public VersionResult getVersionResult(CliType cliType) {
        String commandName = getCommandName(cliType);
        if (!isCommandAvailable(commandName)) {
            return VersionResult.notInstalled();
        }

        String[] commands = getVersionCommands(cliType);
        long versionTimeoutSeconds = getVersionTimeoutSeconds(cliType);
        boolean hasTimeout = false;
        String lastFailure = null;
        for (String command : commands) {
            VersionResult result = runAndParse(command, versionTimeoutSeconds);
            if (result.status() == VersionStatus.INSTALLED) {
                return result;
            }
            if (result.status() == VersionStatus.TIMEOUT) {
                hasTimeout = true;
            } else if (result.status() == VersionStatus.COMMAND_FAILED) {
                lastFailure = result.detail();
            }
        }
        if (hasTimeout) {
            return VersionResult.timeout("version command timeout");
        }
        if (lastFailure != null) {
            return VersionResult.failed(lastFailure);
        }
        return VersionResult.notInstalled();
    }

    public String getLatestVersion(CliType cliType) {
        String command = getLatestVersionCommand(cliType);
        if (command == null) {
            return null;
        }
        VersionResult result = runAndParse(command, LATEST_TIMEOUT_SECONDS);
        return result.status() == VersionStatus.INSTALLED ? result.version() : null;
    }

    public String getUpdateCommand(CliType cliType) {
        return switch (cliType) {
            case CLAUDE -> "claude update";
            case CODEX -> "npm i -g @openai/codex@latest";
            case GEMINI -> "npm i -g @google/gemini-cli@latest";
            case OPENCODE -> "npm i -g opencode-ai@latest";
        };
    }

    public String getInstallCommand(CliType cliType) {
        return switch (cliType) {
            case CLAUDE -> "npm install -g @anthropic-ai/claude-code";
            default -> getUpdateCommand(cliType);
        };
    }

    public String getRecommendedCommand(CliType cliType, VersionStatus status) {
        if (status == VersionStatus.INSTALLED) {
            return getUpdateCommand(cliType);
        }
        return getInstallCommand(cliType);
    }

    private String[] getVersionCommands(CliType cliType) {
        return switch (cliType) {
            case CLAUDE -> new String[]{"claude --version", "claude -v"};
            case CODEX -> new String[]{"codex --version", "codex -v"};
            case GEMINI -> new String[]{"gemini --version", "gemini -v"};
            case OPENCODE -> new String[]{"opencode --version", "opencode -v"};
        };
    }

    private long getVersionTimeoutSeconds(CliType cliType) {
        return cliType == CliType.GEMINI
            ? GEMINI_VERSION_TIMEOUT_SECONDS
            : VERSION_TIMEOUT_SECONDS;
    }

    private String getCommandName(CliType cliType) {
        return switch (cliType) {
            case CLAUDE -> "claude";
            case CODEX -> "codex";
            case GEMINI -> "gemini";
            case OPENCODE -> "opencode";
        };
    }

    private String getLatestVersionCommand(CliType cliType) {
        return switch (cliType) {
            case CLAUDE -> "npm view @anthropic-ai/claude-code version";
            case CODEX -> "npm view @openai/codex version";
            case GEMINI -> "npm view @google/gemini-cli version";
            case OPENCODE -> "npm view opencode-ai version";
        };
    }

    private VersionResult runAndParse(String command, long timeoutSeconds) {
        try {
            ProcessBuilder pb = createProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return VersionResult.timeout("timeout: " + command);
            }

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            String raw = output.toString().trim();

            if (process.exitValue() != 0) {
                if (looksLikeCommandMissing(raw)) {
                    return VersionResult.notInstalled();
                }
                LOG.info("Version command failed (exit " + process.exitValue() + "): " + command);
                return VersionResult.failed("exit " + process.exitValue() + ": " + command);
            }

            String parsed = extractVersion(raw);
            if (parsed == null || parsed.isBlank()) {
                return VersionResult.failed("empty output: " + command);
            }
            return VersionResult.installed(parsed);
        } catch (Exception e) {
            LOG.info("Version command failed: " + command + " -> " + e.getMessage());
            return VersionResult.failed(e.getMessage());
        }
    }

    private boolean isCommandAvailable(String commandName) {
        try {
            ProcessBuilder pb = createCommandCheckProcessBuilder(commandName);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(COMMAND_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOG.info("Command availability check timeout: " + commandName);
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            LOG.info("Command availability check failed: " + commandName + " -> " + e.getMessage());
            return false;
        }
    }

    private ProcessBuilder createProcessBuilder(String command) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return new ProcessBuilder("cmd", "/c", command);
        }
        return new ProcessBuilder("bash", "-c", command);
    }

    private ProcessBuilder createCommandCheckProcessBuilder(String commandName) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return new ProcessBuilder("where.exe", commandName);
        }
        return new ProcessBuilder("bash", "-lc", "command -v " + commandName);
    }

    private boolean looksLikeCommandMissing(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return false;
        }
        String normalized = rawOutput.toLowerCase();
        return normalized.contains("not recognized as an internal or external command")
            || normalized.contains("command not found")
            || normalized.contains("no such file or directory");
    }

    private String extractVersion(String rawOutput) {
        if (rawOutput == null || rawOutput.isEmpty()) {
            return null;
        }

        Matcher matcher = VERSION_PATTERN.matcher(rawOutput);
        if (matcher.find()) {
            return matcher.group(1);
        }

        String firstLine = rawOutput.split("\n")[0].trim();
        return firstLine.isEmpty() ? null : firstLine;
    }
}
