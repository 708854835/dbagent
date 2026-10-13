package com.dbagent.engine.db;

import com.dbagent.engine.cache.CacheSupply;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 字典快照存储：SQLite dict_item 表，全局一份。
 * 整体替换语义（清空重灌，单事务）；冲突时不写库，旧数据保留。
 */
public final class DictStore {

    static final String DDL = """
            CREATE TABLE IF NOT EXISTS dict_item (
              id TEXT PRIMARY KEY,
              chinese_name TEXT NOT NULL,
              english_name TEXT NOT NULL,
              importance TEXT NOT NULL,
              data_type TEXT NOT NULL,
              data_format TEXT,
              code_list_json TEXT,
              code_rule TEXT,
              format_illegal INTEGER NOT NULL DEFAULT 0
            )
            """;

    static final String META_DDL = """
            CREATE TABLE IF NOT EXISTS meta (
              key TEXT PRIMARY KEY,
              value TEXT NOT NULL
            )
            """;

    /** 字典快照标记（meta 表 key）：已确认候选按此标记判定失效。 */
    public static final String SNAPSHOT_KEY = "dict_snapshot";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path dbPath;

    public DictStore(Path dbPath) {
        this.dbPath = dbPath;
    }

    /** 整体替换：清空字典表重灌（单事务），并刷新快照标记（内容哈希）。 */
    public void replaceAll(Collection<CacheSupply.DictItem> items) {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM dict_item");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO dict_item (id, chinese_name, english_name, importance, data_type,"
                            + " data_format, code_list_json, code_rule, format_illegal)"
                            + " VALUES (?,?,?,?,?,?,?,?,?)")) {
                for (CacheSupply.DictItem item : items) {
                    ps.setString(1, item.id());
                    ps.setString(2, item.chineseName());
                    ps.setString(3, item.englishName());
                    ps.setString(4, item.importance());
                    ps.setString(5, item.dataType());
                    ps.setString(6, item.dataFormat());
                    ps.setString(7, codeListJson(item.codeList()));
                    ps.setString(8, item.codeRule());
                    ps.setInt(9, item.formatIllegal() ? 1 : 0);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO meta (key, value) VALUES (?, ?)"
                            + " ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
                ps.setString(1, SNAPSHOT_KEY);
                ps.setString(2, computeSnapshot(items));
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("字典快照导入失败: " + dbPath, e);
        }
    }

    /** 当前字典快照标记（内容哈希）；从未导入时返回空串。 */
    public String currentSnapshot() {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT value FROM meta WHERE key = ?")) {
            ps.setString(1, SNAPSHOT_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        } catch (SQLException e) {
            throw new IllegalStateException("字典快照标记查询失败: " + dbPath, e);
        }
    }

    /** 快照标记 = 全部条目关键内容的 SHA-256（内容不变则标记不变，确认缓存不失效）。 */
    private static String computeSnapshot(Collection<CacheSupply.DictItem> items) {
        String canonical = items.stream()
                .map(i -> String.join("|", i.id(), i.chineseName(), i.englishName(),
                        i.importance(), i.dataType(),
                        i.dataFormat() == null ? "" : i.dataFormat(),
                        codeListJson(i.codeList()) == null ? "" : codeListJson(i.codeList())))
                .sorted()
                .reduce("", (a, b) -> a + "\u0001" + b);
        return sha256Hex(canonical);
    }

    /** SHA-256 十六进制（快照标记与上下文指纹共用）。 */
    public static String sha256Hex(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 按中文名查字典项，公共集优先返回。 */
    public List<CacheSupply.DictItem> findByChineseName(String chineseName) {
        String sql = "SELECT * FROM dict_item WHERE chinese_name = ?"
                + " ORDER BY CASE importance WHEN '公共集' THEN 0 ELSE 1 END, id";
        List<CacheSupply.DictItem> result = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, chineseName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new CacheSupply.DictItem(
                            rs.getString("id"),
                            rs.getString("chinese_name"),
                            rs.getString("english_name"),
                            rs.getString("importance"),
                            rs.getString("data_type"),
                            rs.getString("data_format"),
                            parseCodeList(rs.getString("code_list_json")),
                            rs.getString("code_rule"),
                            rs.getInt("format_illegal") != 0));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("字典快照查询失败: " + dbPath, e);
        }
        return result;
    }

    public int count() {
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM dict_item")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("字典快照统计失败: " + dbPath, e);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    private static String codeListJson(List<CacheSupply.Code> codeList) {
        if (codeList == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(codeList);
        } catch (Exception e) {
            throw new IllegalStateException("代码清单序列化失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<CacheSupply.Code> parseCodeList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json,
                    JSON.getTypeFactory().constructCollectionType(List.class, CacheSupply.Code.class));
        } catch (Exception e) {
            return List.of();
        }
    }
}
