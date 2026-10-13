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
 * 各表 DDL 就位：已确认候选（ConfirmedStore）、字典快照与 meta（DictStore）、Schema 缓存（CacheStore）。
 */
public final class EngineDatabase {

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
            stmt.execute(ConfirmedStore.DDL);
            stmt.execute(DictStore.DDL);
            stmt.execute(DictStore.META_DDL);
            stmt.execute(CacheStore.DDL);
            stmt.execute(CacheStore.INDEX_DDL);
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 初始化失败: " + db, e);
        }
        return db;
    }
}
