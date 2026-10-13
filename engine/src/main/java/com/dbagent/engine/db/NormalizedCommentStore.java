package com.dbagent.engine.db;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 离线 COMMENT 规范化结果独立落库：（系统+表名+字段名）→ 规范化 COMMENT。
 * 与 Schema 缓存解耦：缓存刷新后按键回填（原文一致才替换，防陈旧），不重新跑规范化。
 */
public final class NormalizedCommentStore {

    static final String DDL = """
            CREATE TABLE IF NOT EXISTS normalized_comment (
              owning_system TEXT NOT NULL,
              table_name TEXT NOT NULL,
              column_name TEXT NOT NULL,
              raw_comment TEXT NOT NULL,
              normalized_name TEXT NOT NULL,
              normalized_json TEXT NOT NULL,
              source TEXT NOT NULL,
              PRIMARY KEY (owning_system, table_name, column_name)
            )
            """;

    private final Path dbPath;

    public NormalizedCommentStore(Path dbPath) {
        this.dbPath = dbPath;
    }

    /**
     * @param source 来源：deterministic（确定性清洗）/ llm（LLM 兜底）
     */
    public record Entry(String tableName, String columnName, String rawComment,
                        String normalizedName, String normalizedJson, String source) {
    }

    /** 幂等覆盖：同键新结果直接替换（原文变化后重跑规范化会覆盖旧值）。 */
    public void upsert(String owningSystem, Entry e) {
        String sql = "INSERT OR REPLACE INTO normalized_comment"
                + " (owning_system, table_name, column_name, raw_comment, normalized_name,"
                + " normalized_json, source) VALUES (?,?,?,?,?,?,?)";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, owningSystem);
            ps.setString(2, e.tableName());
            ps.setString(3, e.columnName());
            ps.setString(4, e.rawComment());
            ps.setString(5, e.normalizedName());
            ps.setString(6, e.normalizedJson());
            ps.setString(7, e.source());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("规范化 COMMENT 落库失败: " + owningSystem, ex);
        }
    }

    /**
     * 已规范化跳过判定：同键且当前缓存 COMMENT 等于落库原文或规范化名
     * （回填后缓存值即规范化名，重跑 CLI 不重复调 LLM）。
     */
    public boolean isNormalized(String owningSystem, String tableName, String columnName,
                                String currentComment) {
        String sql = "SELECT 1 FROM normalized_comment"
                + " WHERE owning_system = ? AND table_name = ? AND column_name = ?"
                + " AND (raw_comment = ? OR normalized_name = ?) LIMIT 1";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, owningSystem);
            ps.setString(2, tableName);
            ps.setString(3, columnName);
            ps.setString(4, currentComment);
            ps.setString(5, currentComment);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("规范化 COMMENT 查询失败: " + owningSystem, ex);
        }
    }

    public int count(String owningSystem) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM normalized_comment WHERE owning_system = ?")) {
            ps.setString(1, owningSystem);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("规范化 COMMENT 统计失败: " + owningSystem, ex);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }
}
