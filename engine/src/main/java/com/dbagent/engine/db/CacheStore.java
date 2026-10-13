package com.dbagent.engine.db;

import com.dbagent.engine.cache.CacheSupply;

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
 * 本系统 Schema 缓存：SQLite 存"系统 → 全量字段定义"（含视图字段）。
 * 生成完全离线读缓存；缓存按系统维度隔离。拉取刷新见 SchemaCacheRefresher。
 */
public final class CacheStore {

    static final String DDL = """
            CREATE TABLE IF NOT EXISTS schema_field (
              owning_system TEXT NOT NULL,
              table_name TEXT NOT NULL,
              column_name TEXT NOT NULL,
              column_type TEXT NOT NULL,
              column_comment TEXT NOT NULL
            )
            """;

    static final String INDEX_DDL = """
            CREATE INDEX IF NOT EXISTS idx_schema_field_comment
            ON schema_field (owning_system, column_comment)
            """;

    private final Path dbPath;

    public CacheStore(Path dbPath) {
        this.dbPath = dbPath;
    }

    /** 拉取到的存量字段行。 */
    public record Row(String tableName, String columnName, String columnType, String columnComment) {
    }

    /** 按系统整体替换缓存（单事务）。 */
    public void replaceSystem(String owningSystem, Collection<Row> rows) {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM schema_field WHERE owning_system = ?")) {
                del.setString(1, owningSystem);
                del.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO schema_field (owning_system, table_name, column_name, column_type,"
                            + " column_comment) VALUES (?,?,?,?,?)")) {
                for (Row row : rows) {
                    ps.setString(1, owningSystem);
                    ps.setString(2, row.tableName());
                    ps.setString(3, row.columnName());
                    ps.setString(4, row.columnType());
                    ps.setString(5, row.columnComment());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Schema 缓存写入失败: " + owningSystem, e);
        }
    }

    /**
     * 复用查找：COMMENT 中文名精确匹配（忽略首尾空白，不做分词/相似度）。
     */
    public List<CacheSupply.CachedField> findByComment(String owningSystem, String chineseName) {
        String sql = "SELECT column_name, column_type, column_comment FROM schema_field"
                + " WHERE owning_system = ? AND TRIM(column_comment) = ?";
        List<CacheSupply.CachedField> result = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, owningSystem);
            ps.setString(2, chineseName.trim());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new CacheSupply.CachedField(
                            rs.getString("column_comment").trim(),
                            rs.getString("column_name"),
                            rs.getString("column_type")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Schema 缓存查询失败: " + owningSystem, e);
        }
        return result;
    }

    public int count(String owningSystem) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM schema_field WHERE owning_system = ?")) {
            ps.setString(1, owningSystem);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Schema 缓存统计失败: " + owningSystem, e);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }
}
