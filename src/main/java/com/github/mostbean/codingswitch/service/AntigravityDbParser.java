package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.SessionMessage;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Antigravity CLI 会话数据库解析器。
 *
 * <p>Antigravity CLI 自 2026 年 6 月起将会话记录改用 SQLite 存储（{@code conversations/<id>.db}），
 * 每个数据库的 {@code steps} 表逐行记录一个对话步骤，正文保存在 {@code step_payload} 的
 * protobuf 二进制中。此前的旧版会话使用加密的 {@code .pb} 格式，无法解析。</p>
 *
 * <p>本类封装两件事：只读方式打开 SQLite，以及一个轻量的 protobuf wire-format 解析器
 * （无需 .proto 定义，仅按字段号导航），从中提取用户输入与模型回复的纯文本。</p>
 *
 * <p>字段映射（逆向自实际数据）：</p>
 * <pre>
 *   step_payload
 *     field 1  (varint) = step_type：14=用户输入，15=模型回复
 *     field 5  (message) = 公共元信息，field5.field1.field1(varint) = 创建时间（unix 秒）
 *     field 19 (message) = 用户输入载荷（step_type=14），field19.field2(string) = 用户文本
 *     field 20 (message) = 模型回复载荷（step_type=15），field20.field1|field8(string) = 回复正文
 * </pre>
 */
final class AntigravityDbParser {

    private static final Logger LOG = Logger.getInstance(AntigravityDbParser.class);

    private static final int STEP_TYPE_USER_INPUT = 14;
    private static final int STEP_TYPE_MODEL_RESPONSE = 15;

    private AntigravityDbParser() {
    }

    /**
     * 从会话数据库中读取全部用户与模型消息，按步骤顺序排列。
     */
    static List<SessionMessage> loadMessages(Path dbFile) {
        List<SessionMessage> messages = new ArrayList<>();
        String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            LOG.warn("SQLite JDBC driver unavailable", e);
            return messages;
        }
        try (Connection connection = DriverManager.getConnection(url);
             PreparedStatement ps = connection.prepareStatement(
                 "select step_payload from steps order by idx");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                byte[] payload = rs.getBytes(1);
                if (payload == null || payload.length == 0) {
                    continue;
                }
                SessionMessage message = parseStep(payload);
                if (message != null) {
                    messages.add(message);
                }
            }
        } catch (SQLException e) {
            LOG.warn("Failed to read Antigravity session database: " + dbFile, e);
        }
        return messages;
    }

    /**
     * 读取会话数据库中首条用户输入文本，作为会话摘要。读取失败或无用户输入时返回 {@code null}。
     */
    static String readFirstUserText(Path dbFile) {
        String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            return null;
        }
        try (Connection connection = DriverManager.getConnection(url);
             PreparedStatement ps = connection.prepareStatement(
                 "select step_payload from steps order by idx");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                byte[] payload = rs.getBytes(1);
                if (payload == null || payload.length == 0) {
                    continue;
                }
                SessionMessage message = parseStep(payload);
                if (message != null && "user".equals(message.getRole())) {
                    return message.getContent();
                }
            }
        } catch (SQLException e) {
            LOG.debug("Failed to read Antigravity session summary: " + dbFile, e);
        }
        return null;
    }

    private static SessionMessage parseStep(byte[] payload) {
        Map<Integer, List<Field>> step = decode(payload);
        Long stepType = firstVarint(step, 1);
        if (stepType == null) {
            return null;
        }

        Long timestamp = extractTimestamp(step);

        if (stepType == STEP_TYPE_USER_INPUT) {
            byte[] userLoad = firstBytes(step, 19);
            if (userLoad == null) {
                return null;
            }
            String text = firstString(decode(userLoad), 2);
            if (text == null || text.isBlank()) {
                return null;
            }
            return new SessionMessage("user", text.trim(), timestamp);
        }

        if (stepType == STEP_TYPE_MODEL_RESPONSE) {
            byte[] modelLoad = firstBytes(step, 20);
            if (modelLoad == null) {
                return null;
            }
            Map<Integer, List<Field>> load = decode(modelLoad);
            String text = firstString(load, 1);
            if (text == null || text.isBlank()) {
                text = firstString(load, 8);
            }
            if (text == null || text.isBlank()) {
                return null;
            }
            return new SessionMessage("assistant", text.trim(), timestamp);
        }

        return null;
    }

    /**
     * 提取步骤创建时间：{@code field5.field1.field1}（unix 秒）转毫秒。
     */
    private static Long extractTimestamp(Map<Integer, List<Field>> step) {
        byte[] meta = firstBytes(step, 5);
        if (meta == null) {
            return null;
        }
        byte[] createdAt = firstBytes(decode(meta), 1);
        if (createdAt == null) {
            return null;
        }
        Long seconds = firstVarint(decode(createdAt), 1);
        if (seconds == null || seconds <= 0) {
            return null;
        }
        return seconds * 1000;
    }

    // =====================================================================
    // 轻量 protobuf wire-format 解析
    // =====================================================================

    private record Field(int wireType, long varint, byte[] bytes) {
    }

    /**
     * 将 protobuf 消息解析为「字段号 -> 字段值列表」，仅支持 varint(0)、64 位(1)、
     * length-delimited(2)、32 位(5) 四种 wire type。解析中途出错时返回已解析的部分。
     */
    private static Map<Integer, List<Field>> decode(byte[] data) {
        Map<Integer, List<Field>> result = new HashMap<>();
        int i = 0;
        int n = data.length;
        try {
            while (i < n) {
                long[] tagRead = readVarint(data, i);
                long tag = tagRead[0];
                i = (int) tagRead[1];
                int fieldNumber = (int) (tag >>> 3);
                int wireType = (int) (tag & 0x7);
                if (fieldNumber == 0) {
                    break;
                }
                switch (wireType) {
                    case 0 -> {
                        long[] v = readVarint(data, i);
                        i = (int) v[1];
                        addField(result, fieldNumber, new Field(0, v[0], null));
                    }
                    case 1 -> {
                        if (i + 8 > n) {
                            return result;
                        }
                        i += 8;
                        addField(result, fieldNumber, new Field(1, 0, null));
                    }
                    case 2 -> {
                        long[] lenRead = readVarint(data, i);
                        int len = (int) lenRead[0];
                        i = (int) lenRead[1];
                        if (len < 0 || i + len > n) {
                            return result;
                        }
                        byte[] slice = new byte[len];
                        System.arraycopy(data, i, slice, 0, len);
                        i += len;
                        addField(result, fieldNumber, new Field(2, 0, slice));
                    }
                    case 5 -> {
                        if (i + 4 > n) {
                            return result;
                        }
                        i += 4;
                        addField(result, fieldNumber, new Field(5, 0, null));
                    }
                    default -> {
                        return result;
                    }
                }
            }
        } catch (RuntimeException e) {
            // 遇到非预期字节时返回已解析部分，避免影响其它步骤
            return result;
        }
        return result;
    }

    /**
     * 读取一个 varint，返回 [值, 新偏移]。
     */
    private static long[] readVarint(byte[] data, int offset) {
        long value = 0;
        int shift = 0;
        int i = offset;
        while (i < data.length) {
            byte b = data[i++];
            value |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                return new long[]{value, i};
            }
            shift += 7;
            if (shift >= 64) {
                break;
            }
        }
        return new long[]{value, i};
    }

    private static void addField(Map<Integer, List<Field>> map, int field, Field value) {
        map.computeIfAbsent(field, k -> new ArrayList<>()).add(value);
    }

    private static Long firstVarint(Map<Integer, List<Field>> map, int field) {
        List<Field> fields = map.get(field);
        if (fields == null) {
            return null;
        }
        for (Field f : fields) {
            if (f.wireType() == 0) {
                return f.varint();
            }
        }
        return null;
    }

    private static byte[] firstBytes(Map<Integer, List<Field>> map, int field) {
        List<Field> fields = map.get(field);
        if (fields == null) {
            return null;
        }
        for (Field f : fields) {
            if (f.wireType() == 2 && f.bytes() != null) {
                return f.bytes();
            }
        }
        return null;
    }

    private static String firstString(Map<Integer, List<Field>> map, int field) {
        byte[] bytes = firstBytes(map, field);
        if (bytes == null) {
            return null;
        }
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
