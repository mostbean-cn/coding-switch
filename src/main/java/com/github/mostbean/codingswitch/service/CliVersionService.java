package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.CliType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
    private static final String OFFICIAL_NPM_REGISTRY = "https://registry.npmjs.org/";

    public enum VersionStatus {
        INSTALLED,
        NOT_INSTALLED,
        TIMEOUT,
        COMMAND_FAILED
    }

    private static final class CommandOutput {
        private final Integer exitCode;
        private final String output;
        private final String detail;
        private final boolean timedOut;

        private CommandOutput(
            Integer exitCode,
            String output,
            String detail,
            boolean timedOut
        ) {
            this.exitCode = exitCode;
            this.output = output;
            this.detail = detail;
            this.timedOut = timedOut;
        }

        public static CommandOutput completed(int exitCode, String output) {
            return new CommandOutput(exitCode, output, null, false);
        }

        public static CommandOutput timeout(String detail) {
            return new CommandOutput(null, null, detail, true);
        }

        public static CommandOutput failed(String detail) {
            return new CommandOutput(null, null, detail, false);
        }

        public Integer exitCode() {
            return exitCode;
        }

        public String output() {
            return output;
        }

        public String detail() {
            return detail;
        }

        public boolean timedOut() {
            return timedOut;
        }
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
        return getLatestVersion(cliType, null);
    }

    public String getLatestVersion(CliType cliType, String currentVersion) {
        String packageName = getNpmPackageName(cliType);
        if (packageName == null) {
            return null;
        }

        String configuredRegistry = getConfiguredNpmRegistry();
        String latest = getLatestVersionFromRegistry(packageName, configuredRegistry);
        if (
            latest != null &&
            (currentVersion == null ||
                compareVersions(latest, currentVersion) >= 0 ||
                isOfficialRegistry(configuredRegistry))
        ) {
            return latest;
        }

        String officialLatest = getLatestVersionFromRegistry(
            packageName,
            OFFICIAL_NPM_REGISTRY
        );
        return compareVersions(officialLatest, latest) > 0
            ? officialLatest
            : latest;
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

    private String getNpmPackageName(CliType cliType) {
        return switch (cliType) {
            case CLAUDE -> "@anthropic-ai/claude-code";
            case CODEX -> "@openai/codex";
            case GEMINI -> "@google/gemini-cli";
            case OPENCODE -> "opencode-ai";
        };
    }

    private VersionResult runAndParse(String command, long timeoutSeconds) {
        CommandOutput commandOutput = runCommand(command, timeoutSeconds);
        if (commandOutput.timedOut()) {
            return VersionResult.timeout(commandOutput.detail());
        }
        if (commandOutput.exitCode() == null) {
            return VersionResult.failed(commandOutput.detail());
        }

        String raw = commandOutput.output();
        if (commandOutput.exitCode() != 0) {
            if (looksLikeCommandMissing(raw)) {
                return VersionResult.notInstalled();
            }
            LOG.info(
                "Version command failed (exit " +
                commandOutput.exitCode() +
                "): " +
                command
            );
            return VersionResult.failed(
                "exit " + commandOutput.exitCode() + ": " + command
            );
        }

        String parsed = extractVersion(raw);
        if (parsed == null || parsed.isBlank()) {
            return VersionResult.failed("empty output: " + command);
        }
        return VersionResult.installed(parsed);
    }

    public static int compareVersions(String left, String right) {
        if (Objects.equals(normalizeVersion(left), normalizeVersion(right))) {
            return 0;
        }
        if (left == null || left.isBlank()) {
            return -1;
        }
        if (right == null || right.isBlank()) {
            return 1;
        }

        List<Integer> leftParts = parseVersionNumbers(left);
        List<Integer> rightParts = parseVersionNumbers(right);
        int maxSize = Math.max(leftParts.size(), rightParts.size());
        for (int i = 0; i < maxSize; i++) {
            int leftValue = i < leftParts.size() ? leftParts.get(i) : 0;
            int rightValue = i < rightParts.size() ? rightParts.get(i) : 0;
            if (leftValue != rightValue) {
                return Integer.compare(leftValue, rightValue);
            }
        }
        return normalizeVersion(left).compareTo(normalizeVersion(right));
    }

    private CommandOutput runCommand(String command, long timeoutSeconds) {
        try {
            ProcessBuilder pb = createProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return CommandOutput.timeout("timeout: " + command);
            }

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            String raw = output.toString().trim();
            return CommandOutput.completed(process.exitValue(), raw);
        } catch (Exception e) {
            LOG.info("Version command failed: " + command + " -> " + e.getMessage());
            return CommandOutput.failed(e.getMessage());
        }
    }

    private String getLatestVersionFromRegistry(String packageName, String registry) {
        if (registry == null || registry.isBlank()) {
            return null;
        }
        String command =
            "npm view " + packageName + " version --registry=" + registry.trim();
        VersionResult result = runAndParse(command, LATEST_TIMEOUT_SECONDS);
        return result.status() == VersionStatus.INSTALLED ? result.version() : null;
    }

    private String getConfiguredNpmRegistry() {
        CommandOutput result = runCommand(
            "npm config get registry",
            COMMAND_CHECK_TIMEOUT_SECONDS
        );
        if (result.timedOut() || result.exitCode() == null || result.exitCode() != 0) {
            return OFFICIAL_NPM_REGISTRY;
        }
        String registry = result.output();
        if (registry == null || registry.isBlank() || "undefined".equalsIgnoreCase(registry.trim())) {
            return OFFICIAL_NPM_REGISTRY;
        }
        return registry.trim();
    }

    private boolean isOfficialRegistry(String registry) {
        return normalizeRegistry(registry).equals(normalizeRegistry(OFFICIAL_NPM_REGISTRY));
    }

    private static String normalizeRegistry(String registry) {
        if (registry == null) {
            return "";
        }
        String normalized = registry.trim().toLowerCase();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String normalizeVersion(String version) {
        if (version == null) {
            return null;
        }
        return version.trim().replaceFirst("^[vV]", "");
    }

    private static List<Integer> parseVersionNumbers(String version) {
        List<Integer> numbers = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\d+").matcher(normalizeVersion(version));
        while (matcher.find()) {
            numbers.add(Integer.parseInt(matcher.group()));
        }
        return numbers;
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
