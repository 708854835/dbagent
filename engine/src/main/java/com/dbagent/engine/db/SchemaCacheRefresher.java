package com.dbagent.engine.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 本系统 Schema 缓存拉取：IDEA 配置 MySQL 数据源（URL/账号/口令），
 * information_schema 一次性拉全量（含视图）→ SQLite 按系统隔离缓存。
 * 同一事务连接内完成全量读取，保证快照一致。
 */
public final class SchemaCacheRefresher {

    private static final String PULL_SQL = """
            SELECT c.TABLE_NAME, c.COLUMN_NAME, c.COLUMN_TYPE, c.COLUMN_COMMENT
            FROM information_schema.COLUMNS c
            JOIN information_schema.TABLES t
              ON t.TABLE_SCHEMA = c.TABLE_SCHEMA AND t.TABLE_NAME = c.TABLE_NAME
            WHERE c.TABLE_SCHEMA = ? AND t.TABLE_TYPE IN ('BASE TABLE', 'VIEW')
            ORDER BY c.TABLE_NAME, c.ORDINAL_POSITION
            """;

    private SchemaCacheRefresher() {
    }

    /** 拉取统计：表数（distinct 表名）与字段行数。 */
    public record RefreshStats(int tables, int fields) {
    }

    /**
     * 拉取并整体替换该系统的缓存。
     *
     * @return 拉取统计（表数 / 字段行数）
     */
    public static RefreshStats refresh(String jdbcUrl, String username, String password,
                                       String owningSystem, CacheStore store) {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
            conn.setReadOnly(true);
            String schema = conn.getCatalog();
            List<CacheStore.Row> rows = new ArrayList<>();
            Set<String> tables = new HashSet<>();
            try (PreparedStatement ps = conn.prepareStatement(PULL_SQL)) {
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String tableName = rs.getString("TABLE_NAME");
                        tables.add(tableName);
                        rows.add(new CacheStore.Row(
                                tableName,
                                rs.getString("COLUMN_NAME"),
                                rs.getString("COLUMN_TYPE"),
                                rs.getString("COLUMN_COMMENT") == null
                                        ? "" : rs.getString("COLUMN_COMMENT")));
                    }
                }
            }
            store.replaceSystem(owningSystem, rows);
            return new RefreshStats(tables.size(), rows.size());
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Schema 缓存拉取失败: " + jdbcUrl + " (system=" + owningSystem + ")", e);
        }
    }
}
