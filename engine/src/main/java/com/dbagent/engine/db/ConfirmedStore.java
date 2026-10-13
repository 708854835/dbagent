package com.dbagent.engine.db;

import com.dbagent.engine.cache.CacheSupply;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 已确认候选存储：用户复核通过（LLM 候选/手工修改）的字段定义，
 * 按 归属系统+字段中文名+上下文指纹 落 SQLite，后续生成同上下文直接复用（不再走 LLM）。
 * 每条记录登记时绑定当前字典快照标记；字典快照更新后旧候选失效（查询过滤，不物理删除）。
 */
public final class ConfirmedStore {

    static final String DDL = """
            CREATE TABLE IF NOT EXISTS confirmed_candidate (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              owning_system TEXT NOT NULL,
              chinese_name TEXT NOT NULL,
              context_fingerprint TEXT NOT NULL,
              english_name TEXT NOT NULL,
              data_type TEXT,
              physical_type TEXT NOT NULL,
              code_list_json TEXT,
              dict_snapshot TEXT NOT NULL DEFAULT '',
              created_at TEXT NOT NULL DEFAULT (datetime('now')),
              UNIQUE (owning_system, chinese_name, context_fingerprint)
            )
            """;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path dbPath;

    public ConfirmedStore(Path dbPath) {
        this.dbPath = dbPath;
        // T1 旧库轻量迁移：补 data_type / dict_snapshot 列（已存在则忽略）
        ensureColumn("data_type", "TEXT");
        ensureColumn("dict_snapshot", "TEXT NOT NULL DEFAULT ''");
    }

    private void ensureColumn(String name, String type) {
        try (Connection conn = connect();
             var stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE confirmed_candidate ADD COLUMN " + name + " " + type);
        } catch (SQLException e) {
            if (e.getMessage() == null || !e.getMessage().contains("duplicate column")) {
                throw new IllegalStateException("confirmed_candidate 迁移失败: " + name, e);
            }
        }
    }

    /** 登记/覆盖一条已确认候选（同 系统+中文名+指纹 幂等覆盖）。 */
    public void save(String owningSystem, String chineseName, String contextFingerprint,
                     String englishName, String dataType, String physicalType,
                     List<CacheSupply.Code> codeList, String dictSnapshot) {
        String sql = "INSERT INTO confirmed_candidate (owning_system, chinese_name,"
                + " context_fingerprint, english_name, data_type, physical_type,"
                + " code_list_json, dict_snapshot) VALUES (?,?,?,?,?,?,?,?)"
                + " ON CONFLICT(owning_system, chinese_name, context_fingerprint)"
                + " DO UPDATE SET english_name = excluded.english_name,"
                + " data_type = excluded.data_type, physical_type = excluded.physical_type,"
                + " code_list_json = excluded.code_list_json,"
                + " dict_snapshot = excluded.dict_snapshot";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, owningSystem);
            ps.setString(2, chineseName);
            ps.setString(3, contextFingerprint);
            ps.setString(4, englishName);
            ps.setString(5, dataType);
            ps.setString(6, physicalType);
            ps.setString(7, codeList == null ? null : JSON.writeValueAsString(codeList));
            ps.setString(8, dictSnapshot == null ? "" : dictSnapshot);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("已确认候选登记失败: " + dbPath, e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("代码清单序列化失败", e);
        }
    }

    /** 按系统+中文名查有效候选（仅返回字典快照标记一致的记录；快照已更新的旧候选失效）。 */
    public List<CacheSupply.ConfirmedCandidate> findValid(String owningSystem, String chineseName,
                                                          String currentSnapshot) {
        String sql = "SELECT chinese_name, english_name, data_type, physical_type,"
                + " code_list_json, context_fingerprint FROM confirmed_candidate"
                + " WHERE owning_system = ? AND chinese_name = ? AND dict_snapshot = ?";
        List<CacheSupply.ConfirmedCandidate> result = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, owningSystem);
            ps.setString(2, chineseName);
            ps.setString(3, currentSnapshot == null ? "" : currentSnapshot);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new CacheSupply.ConfirmedCandidate(
                            rs.getString("chinese_name"),
                            rs.getString("english_name"),
                            rs.getString("data_type"),
                            rs.getString("physical_type"),
                            parseCodeList(rs.getString("code_list_json")),
                            rs.getString("context_fingerprint")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("已确认候选查询失败: " + dbPath, e);
        }
        return result;
    }

    /** 候选总数（含已失效记录，演示/排障用）。 */
    public int count() {
        try (Connection conn = connect();
             var stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM confirmed_candidate")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("已确认候选统计失败: " + dbPath, e);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
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
