package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.AiCompletionLengthLevel;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service(Service.Level.APP)
public final class AiCommitMessageService {

    private static final int MAX_TOTAL_CHARS = 12000;
    private static final int MAX_FILE_CONTENT_CHARS = 1800;
    private static final Pattern CONVENTIONAL_COMMIT_PATTERN = Pattern.compile(
        "^(feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert)(\\([^)]+\\))?!?:\\s+.+",
        Pattern.CASE_INSENSITIVE
    );

    public static AiCommitMessageService getInstance() {
        return ApplicationManager.getApplication().getService(AiCommitMessageService.class);
    }

    public Optional<String> generate(Iterable<Change> changes) throws Exception {
        return generate(changes, List.of());
    }

    public Optional<String> generate(Iterable<Change> changes, Iterable<?> unversionedFiles) throws Exception {
        List<Change> changeList = toList(changes);
        List<?> unversionedFileList = toObjectList(unversionedFiles);
        if (changeList.isEmpty() && unversionedFileList.isEmpty()) {
            return Optional.empty();
        }

        String systemPrompt = """
            你是 Git 提交信息生成器。
            你的输出必须是最终提交信息本身，不能包含任何对话、确认、解释、Markdown、代码块或引号。
            不能调用工具，不能输出 tool_call、Bash、git status、system、reminder 等工具调用或系统消息文本。
            不要说需要先查看代码变更；下面已经提供了当前选中文件的变更内容。
            禁止输出“明白”“好的”“我会”“下面是”“以下是”等前缀。
            优先使用 Conventional Commits 格式：type(scope): 中文摘要。
            第一行必须是提交标题，格式为 type(scope): 中文摘要。
            标题和列表都要描述“用户可理解的功能变化或行为变化”，不要描述“改了哪些类”。
            除非类名、方法名、文件名本身就是用户需要关心的公开 API，否则不要在提交信息中出现类名、方法名或文件名。
            遇到 Java 类、服务、配置页、缓存等内部实现时，请概括成模块能力，例如“补全请求”“模型配置”“设置界面”。
            如果涉及多个文件或多个功能点，标题后空一行，然后用中文短横线列表概括主要用户可见变化。
            每条列表必须来自 diff 中的实际变更，不要编造未出现的功能，也不要逐个罗列内部类的改动。
            列表不超过 5 条，每条不超过 40 个中文字符。
            除非代码变更明显要求英文，否则使用简体中文。
            """;
        String userPrompt = """
            请只根据下面的文件变更生成 Git 提交信息。
            直接输出提交信息，不要输出任何其他文字。
            请把内部实现名转换成更容易理解的模块描述，尽量少出现类名、方法名和文件名。
            推荐格式：
            type(scope): 中文摘要

            - 面向用户或维护者可理解的变化 1
            - 面向用户或维护者可理解的变化 2

            """ + buildChangesSummary(changeList, unversionedFileList);
        return AiCompletionService.getInstance().generateGitCommitText(
            systemPrompt,
            userPrompt,
            AiCompletionLengthLevel.LONG
        ).map(this::sanitizeCommitMessage)
            .map(this::simplifyImplementationNames)
            .map(value -> value.isBlank() ? fallbackCommitMessage(changeList, unversionedFileList) : value)
            .filter(value -> !value.isBlank());
    }

    private List<Change> toList(Iterable<Change> changes) {
        List<Change> list = new ArrayList<>();
        if (changes == null) {
            return list;
        }
        for (Change change : changes) {
            if (change != null) {
                list.add(change);
            }
        }
        return list;
    }

    private List<?> toObjectList(Iterable<?> values) {
        List<Object> list = new ArrayList<>();
        if (values == null) {
            return list;
        }
        for (Object value : values) {
            if (value != null) {
                list.add(value);
            }
        }
        return list;
    }

    private String buildChangesSummary(List<Change> changes, List<?> unversionedFiles) {
        StringBuilder out = new StringBuilder();
        out.append("下面是当前已选择提交文件的 Git diff，包含具体代码变更内容：\n\n");
        for (Change change : changes) {
            if (out.length() >= MAX_TOTAL_CHARS) {
                out.append("\n... truncated ...\n");
                break;
            }
            appendChangeDiff(out, change);
        }
        for (Object filePath : unversionedFiles) {
            if (out.length() >= MAX_TOTAL_CHARS) {
                out.append("\n... truncated ...\n");
                break;
            }
            appendUnversionedFileDiff(out, filePath);
        }
        return trimToLimit(out.toString(), MAX_TOTAL_CHARS);
    }

    private String resolvePath(Change change) {
        ContentRevision after = change.getAfterRevision();
        if (after != null && after.getFile() != null) {
            return after.getFile().getPath();
        }
        ContentRevision before = change.getBeforeRevision();
        if (before != null && before.getFile() != null) {
            return before.getFile().getPath();
        }
        return change.toString();
    }

    private void appendChangeDiff(StringBuilder out, Change change) {
        String path = resolvePath(change);
        String before = readRevisionContent(change.getBeforeRevision());
        String after = readRevisionContent(change.getAfterRevision());

        out.append("diff --git a/").append(path).append(" b/").append(path).append("\n");
        out.append("Change-Type: ").append(change.getType()).append("\n");
        out.append("--- a/").append(path).append("\n");
        out.append("+++ b/").append(path).append("\n");
        if (before == null && after == null) {
            out.append("@@ content unavailable @@\n");
        } else if (before == null) {
            appendPrefixedLines(out, "+", after);
        } else if (after == null) {
            appendPrefixedLines(out, "-", before);
        } else {
            appendFocusedDiff(out, before, after);
        }
        out.append("\n");
    }

    private void appendUnversionedFileDiff(StringBuilder out, Object filePath) {
        String path = resolveFilePathText(filePath);
        String content = readFilePathContent(filePath);
        out.append("diff --git a/").append(path).append(" b/").append(path).append("\n");
        out.append("Change-Type: NEW\n");
        out.append("--- /dev/null\n");
        out.append("+++ b/").append(path).append("\n");
        appendPrefixedLines(out, "+", trimToLimit(content, MAX_FILE_CONTENT_CHARS));
        out.append("\n");
    }

    private String resolveFilePathText(Object filePath) {
        Object path = invokeNoArg(filePath, "getPath");
        if (path instanceof String value && !value.isBlank()) {
            return value;
        }
        Object ioFile = invokeNoArg(filePath, "getIOFile");
        if (ioFile instanceof File file) {
            return file.getPath();
        }
        return filePath == null ? "unknown" : filePath.toString();
    }

    private String readFilePathContent(Object filePath) {
        Object ioFile = invokeNoArg(filePath, "getIOFile");
        if (ioFile instanceof File file && file.isFile()) {
            try {
                return Files.readString(file.toPath(), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                return "<content unavailable>";
            }
        }
        return "<content unavailable>";
    }

    private Object invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            return target.getClass().getMethod(methodName).invoke(target);
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException ignored) {
            return null;
        }
    }

    private String readRevisionContent(ContentRevision revision) {
        if (revision == null) {
            return null;
        }
        try {
            String content = revision.getContent();
            return content == null ? "" : trimToLimit(content, MAX_FILE_CONTENT_CHARS);
        } catch (VcsException e) {
            return null;
        }
    }

    private void appendFocusedDiff(StringBuilder out, String before, String after) {
        String[] beforeLines = before.split("\\n", -1);
        String[] afterLines = after.split("\\n", -1);

        int prefix = 0;
        int maxPrefix = Math.min(beforeLines.length, afterLines.length);
        while (prefix < maxPrefix && beforeLines[prefix].equals(afterLines[prefix])) {
            prefix++;
        }

        int beforeSuffix = beforeLines.length - 1;
        int afterSuffix = afterLines.length - 1;
        while (beforeSuffix >= prefix
            && afterSuffix >= prefix
            && beforeLines[beforeSuffix].equals(afterLines[afterSuffix])) {
            beforeSuffix--;
            afterSuffix--;
        }

        int contextStart = Math.max(0, prefix - 3);
        int beforeContextEnd = Math.min(beforeLines.length - 1, beforeSuffix + 3);
        int afterContextEnd = Math.min(afterLines.length - 1, afterSuffix + 3);
        out.append("@@ selected change @@\n");
        for (int i = contextStart; i < prefix && i < beforeLines.length; i++) {
            out.append(" ").append(beforeLines[i]).append("\n");
        }
        for (int i = prefix; i <= beforeSuffix && i < beforeLines.length; i++) {
            out.append("-").append(beforeLines[i]).append("\n");
        }
        for (int i = prefix; i <= afterSuffix && i < afterLines.length; i++) {
            out.append("+").append(afterLines[i]).append("\n");
        }
        for (int i = Math.max(prefix, afterSuffix + 1); i <= afterContextEnd && i < afterLines.length; i++) {
            out.append(" ").append(afterLines[i]).append("\n");
        }
        if (beforeContextEnd + afterContextEnd < beforeLines.length + afterLines.length - 2) {
            out.append("... diff truncated ...\n");
        }
    }

    private void appendPrefixedLines(StringBuilder out, String prefix, String content) {
        if (content == null || content.isBlank()) {
            out.append(prefix).append("<empty>\n");
            return;
        }
        for (String line : content.split("\\n", -1)) {
            out.append(prefix).append(line).append("\n");
        }
    }

    private String trimToLimit(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value == null ? "" : value;
        }
        return value.substring(0, limit) + "\n... truncated ...";
    }

    private String sanitizeCommitMessage(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim()
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replaceAll("(?s)^```[a-zA-Z0-9_-]*\\s*", "")
            .replaceAll("(?s)\\s*```$", "")
            .trim();
        if (text.isBlank()) {
            return "";
        }
        if (looksLikeToolCall(text)) {
            return extractConventionalCommit(text).orElse("");
        }

        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\n")) {
            String normalized = normalizeOutputLine(line);
            if (!normalized.isBlank() && !isMetaResponseLine(normalized)) {
                lines.add(normalized);
            }
        }
        if (lines.isEmpty()) {
            return "";
        }

        for (int i = 0; i < lines.size(); i++) {
            if (CONVENTIONAL_COMMIT_PATTERN.matcher(lines.get(i)).matches()) {
                return joinCommitLines(lines.subList(i, lines.size()));
            }
        }
        return joinCommitLines(lines);
    }

    private Optional<String> extractConventionalCommit(String text) {
        for (String line : text.split("\\n")) {
            String normalized = normalizeOutputLine(line);
            if (CONVENTIONAL_COMMIT_PATTERN.matcher(normalized).matches()) {
                return Optional.of(normalized);
            }
        }
        return Optional.empty();
    }

    private String normalizeOutputLine(String line) {
        String value = line == null ? "" : line.trim();
        value = value.replaceFirst("^>\\s*", "")
            .trim();
        while ((value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("'") && value.endsWith("'"))
            || (value.startsWith("`") && value.endsWith("`"))) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private boolean isMetaResponseLine(String line) {
        if (CONVENTIONAL_COMMIT_PATTERN.matcher(line).matches()) {
            return false;
        }
        String lower = line.toLowerCase();
        return line.matches("^(明白|好的|可以|当然|我会|下面是|以下是|已根据|根据).*$")
            || line.matches("^(我需要|需要先|请先|请提供|无法|不能|没有看到|未提供).*$")
            || lower.matches("^(sure|ok|okay|here is|here's|i will|i can).*$")
            || line.contains("仅返回提交信息")
            || line.contains("提交信息本身")
            || lower.contains("conventional commits 规范")
            || lower.contains("commit message itself");
    }

    private boolean looksLikeToolCall(String text) {
        String lower = text.toLowerCase();
        return lower.contains("<tool_call")
            || lower.contains("</tool_call")
            || lower.contains("tool_call")
            || lower.contains("git status")
            || lower.contains("bash")
            || lower.contains("/system")
            || lower.contains("-reminder")
            || lower.contains("\\x1b[31m");
    }

    private String simplifyImplementationNames(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (String line : message.split("\\n", -1)) {
            String normalized = line.replaceAll(
                "^(\\s*-\\s+)[A-Z][A-Za-z0-9_]*(?:Service|Builder|Configurable|Manager|Collector|Stats|Cache|Client|Action|Settings|Profile|Index)\\s+",
                "$1"
            );
            normalized = normalized.replaceAll(
                "\\b[A-Z][A-Za-z0-9_]*(?:Service|Builder|Configurable|Manager|Collector|Stats|Cache|Client|Action|Settings|Profile|Index)\\b",
                "相关模块"
            );
            lines.add(normalized);
        }
        return String.join("\n", lines).trim();
    }

    private String fallbackCommitMessage(List<Change> changes, List<?> unversionedFiles) {
        if (changes.isEmpty() && unversionedFiles.isEmpty()) {
            return "";
        }
        boolean allNew = !unversionedFiles.isEmpty()
            && changes.stream().allMatch(change -> change.getType() == Change.Type.NEW);
        boolean allDeleted = changes.stream().allMatch(change -> change.getType() == Change.Type.DELETED);
        String action = allNew ? "添加" : allDeleted ? "删除" : "更新";
        String type = allNew ? "feat" : "chore";

        String scope = inferCommitScope(changes, unversionedFiles);
        String target = inferFallbackTarget(scope);
        if (changes.size() + unversionedFiles.size() > 1) {
            target += "相关能力";
        }
        return type + "(" + scope + "): " + action + target;
    }

    private String inferCommitScope(List<Change> changes, List<?> unversionedFiles) {
        String haystack = collectChangedPathText(changes, unversionedFiles)
            .replace('\\', '/')
            .toLowerCase();
        if (haystack.contains("commitmessage") || haystack.contains("commit-message") || haystack.contains("vcs")) {
            return "commit";
        }
        if (haystack.contains("completion") || haystack.contains("inline") || haystack.contains("fim")) {
            return "ai-completion";
        }
        if (haystack.contains("settings") || haystack.contains("configurable") || haystack.contains("i18n")) {
            return "settings";
        }
        if (haystack.contains("index") || haystack.contains("context") || haystack.contains("collector")) {
            return "context";
        }
        if (haystack.contains("/ui/")) {
            return "ui";
        }
        if (haystack.contains("/test/")) {
            return "test";
        }
        return "project";
    }

    private String inferFallbackTarget(String scope) {
        return switch (scope) {
            case "commit" -> "提交信息生成";
            case "ai-completion" -> "AI 补全";
            case "settings" -> "设置界面";
            case "context" -> "上下文增强";
            case "ui" -> "界面交互";
            case "test" -> "测试覆盖";
            default -> "项目";
        };
    }

    private String collectChangedPathText(List<Change> changes, List<?> unversionedFiles) {
        StringBuilder text = new StringBuilder();
        for (Change change : changes) {
            text.append(resolvePath(change)).append('\n');
        }
        for (Object filePath : unversionedFiles) {
            text.append(resolveFilePathText(filePath)).append('\n');
        }
        return text.toString();
    }

    private String joinCommitLines(List<String> lines) {
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            if (result.size() >= 16) {
                break;
            }
            if (!line.isBlank()) {
                result.add(line);
            }
        }
        if (result.size() > 1 && result.get(1).startsWith("- ")) {
            return result.get(0) + "\n\n" + String.join("\n", result.subList(1, result.size()));
        }
        return String.join("\n", result).trim();
    }
}
