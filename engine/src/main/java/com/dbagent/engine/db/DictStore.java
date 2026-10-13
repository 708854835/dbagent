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

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path dbPath;

    public DictStore(Path dbPath) {
        this.dbPath = dbPath;
    }

    /** 整体替换：清空字典表重灌（单事务）。 */
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
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("字典快照导入失败: " + dbPath, e);
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
