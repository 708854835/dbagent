package com.dbagent.engine.db;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite 落库（data/engine.db，WAL 模式）。
 * T1 切片：库与"已确认候选"表结构就位待用；缓存读写随后续工单接入。
 */
public final class EngineDatabase {

    private static final String CONFIRMED_CANDIDATE_DDL = """
            CREATE TABLE IF NOT EXISTS confirmed_candidate (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              owning_system TEXT NOT NULL,
              chinese_name TEXT NOT NULL,
              context_fingerprint TEXT NOT NULL,
              english_name TEXT NOT NULL,
              physical_type TEXT NOT NULL,
              code_list_json TEXT,
              created_at TEXT NOT NULL DEFAULT (datetime('now')),
              UNIQUE (owning_system, chinese_name, context_fingerprint)
            )
            """;

    private EngineDatabase() {
    }

    public static Path initialize(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException("数据目录创建失败: " + dataDir, e);
        }
        Path db = dataDir.resolve("engine.db").toAbsolutePath();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute(CONFIRMED_CANDIDATE_DDL);
            stmt.execute(DictStore.DDL);
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 初始化失败: " + db, e);
        }
        return db;
    }
}
