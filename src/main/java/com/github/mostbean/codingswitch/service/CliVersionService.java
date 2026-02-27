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
 * 通过执行命令行获取各 CLI 工具的安装状态、当前版本和最新版本。
 */
@Service(Service.Level.APP)
public final class CliVersionService {

    private static final Logger LOG = Logger.getInstance(CliVersionService.class);
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+\\.\\d+[.\\d]*)");

    public static CliVersionService getInstance() {
        return ApplicationManager.getApplication().getService(CliVersionService.class);
    }

    /**
     * 获取指定 CLI 的当前安装版本号。
     *
     * @return 版本号字符串，未安装则返回 null
     */
    public String getVersion(CliType cliType) {
        // 尝试多个命令以提高兼容性
        String[] commands = getVersionCommands(cliType);
        for (String command : commands) {
            String result = runAndParse(command);
            if (result != null)
                return result;
        }
        return null;
    }

    /**
     * 从 npm registry 或其他来源获取最新版本号。
     *
     * @return 最新版本号，获取失败返回 null
     */
    public String getLatestVersion(CliType cliType) {
        String command = getLatestVersionCommand(cliType);
        if (command == null)
            return null;
        return runAndParse(command);
    }

    /**
     * 获取更新命令。
     */
    public String getUpdateCommand(CliType cliType) {
        return switch (cliType) {
            case CLAUDE -> "curl -fsSL https://claude.ai/install.sh | bash";
            case CODEX -> "npm i -g @openai/codex@latest";
            case GEMINI -> "npm i -g @google/gemini-cli@latest";
            case OPENCODE -> "npm i -g opencode-ai@latest";
        };
    }

    /**
     * 获取安装脚本（与更新命令相同）。
     */
    public String getInstallCommand(CliType cliType) {
        return getUpdateCommand(cliType);
    }

    // =====================================================================
    // 内部方法
    // =====================================================================

    /**
     * 每种 CLI 可能需要尝试多个命令来检测版本。
     */
    private String[] getVersionCommands(CliType cliType) {
        return switch (cliType) {
            case CLAUDE -> new String[] { "claude --version", "claude -v" };
            case CODEX -> new String[] { "codex --version", "codex -v", "npx @openai/codex --version" };
            case GEMINI -> new String[] { "gemini --version", "gemini -v", "npx @google/gemini-cli --version" };
            case OPENCODE -> new String[] { "opencode --version", "opencode -v" };
        };
    }

    /**
     * 获取最新版本查询命令。
     * npm 包用 npm view，其他走 --version（暂无公开 API）。
     */
    private String getLatestVersionCommand(CliType cliType) {
        return switch (cliType) {
            case CLAUDE -> "npm view @anthropic-ai/claude-code version";
            case CODEX -> "npm view @openai/codex version";
            case GEMINI -> "npm view @google/gemini-cli version";
            case OPENCODE -> "npm view opencode-ai version";
        };
    }

    /**
     * 执行命令并从输出中提取版本号。
     */
    private String runAndParse(String command) {
        try {
            ProcessBuilder pb = createProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0)
                return null;

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            return extractVersion(output.toString().trim());
        } catch (Exception e) {
            LOG.info("Version command failed: " + command + " -> " + e.getMessage());
            return null;
        }
    }

    private ProcessBuilder createProcessBuilder(String command) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return new ProcessBuilder("cmd", "/c", command);
        } else {
            return new ProcessBuilder("bash", "-c", command);
        }
    }

    /**
     * 从命令输出中提取 x.y.z 格式的版本号。
     */
    private String extractVersion(String rawOutput) {
        if (rawOutput == null || rawOutput.isEmpty())
            return null;

        // 用正则匹配 x.y.z 格式
        Matcher matcher = VERSION_PATTERN.matcher(rawOutput);
        if (matcher.find()) {
            return matcher.group(1);
        }

        // 如果没匹配到版本号格式，返回原始内容（取第一行）
        String firstLine = rawOutput.split("\n")[0].trim();
        return firstLine.isEmpty() ? null : firstLine;
    }
}
