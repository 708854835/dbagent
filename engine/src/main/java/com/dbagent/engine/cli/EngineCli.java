package com.dbagent.engine.cli;

import com.dbagent.engine.cache.ConfirmedService;
import com.dbagent.engine.cache.SqliteCacheSupply;
import com.dbagent.engine.comment.NormalizeCommentService;
import com.dbagent.engine.db.CacheStore;
import com.dbagent.engine.db.ConfirmedStore;
import com.dbagent.engine.db.DictStore;
import com.dbagent.engine.db.EngineDatabase;
import com.dbagent.engine.db.NormalizedCommentStore;
import com.dbagent.engine.db.SchemaCacheRefresher;
import com.dbagent.engine.dict.DictImportReport;
import com.dbagent.engine.dict.DictImporter;
import com.dbagent.engine.form.ConfirmInput;
import com.dbagent.engine.form.FormInput;
import com.dbagent.engine.gen.GenerationException;
import com.dbagent.engine.gen.GenerationResult;
import com.dbagent.engine.gen.GenerationService;
import com.dbagent.engine.http.EngineHttp;
import com.dbagent.engine.http.EngineSettings;
import com.dbagent.engine.llm.LlmAssistService;
import com.dbagent.engine.llm.LlmProviderFactory;
import com.dbagent.engine.rules.EngineConfig;
import com.dbagent.engine.rules.RulesLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpServer;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 引擎 CLI（免 Token、不走 HTTP，直调核心缝）：
 * <pre>
 * generate        --input form.json --output result.json [--config config] [--data data]
 * serve           [--config config] [--data data] [--master-key 主密钥]   启动 HTTP 服务（默认 9090，Token 校验）
 * refresh-schema  --system 系统标识 --jdbc jdbc:mysql://host:3306/db --user 账号 [--password 口令]
 * normalize-comments --system 系统标识 [--batch-size 20] [--batch-interval-ms 1000]
 * </pre>
 */
public final class EngineCli {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private EngineCli() {
    }

    public static void main(String[] args) {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true,
                StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true,
                StandardCharsets.UTF_8));

        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        Map<String, String> opts = parseArgs(args);
        Path configDir = Path.of(opts.getOrDefault("--config", "config"));
        Path dataDir = Path.of(opts.getOrDefault("--data", "data"));

        switch (args[0]) {
            case "generate" -> runGenerate(opts, configDir, dataDir);
            case "confirm" -> runConfirm(opts, configDir, dataDir);
            case "refresh-schema" -> runRefreshSchema(opts, dataDir);
            case "normalize-comments" -> runNormalizeComments(opts, configDir, dataDir);
            case "serve" -> runServe(opts, configDir, dataDir);
            default -> {
                usage();
                System.exit(2);
            }
        }
    }

    private static void runGenerate(Map<String, String> opts, Path configDir, Path dataDir) {
        Path input = Path.of(required(opts, "--input"));
        Path output = Path.of(required(opts, "--output"));
        try {
            EngineConfig config = RulesLoader.load(configDir);
            Path db = EngineDatabase.initialize(dataDir);
            // 字典快照导入（整体替换；冲突回滚并拒绝生成）
            DictStore dictStore = new DictStore(db);
            DictImportReport report = DictImporter.importAll(
                    configDir.resolve("dict"), dictStore, config.typeMapping());
            if (report.totalItems() > 0 || report.hasConflicts()) {
                System.out.println(report.summaryLine());
            }
            if (report.hasConflicts()) {
                writeError(output, "DICT_IMPORT_CONFLICT", report.summaryLine());
                System.exit(1);
            }
            FormInput form = JSON.readValue(input.toFile(), FormInput.class);
            GenerationResult result;
            try (LlmAssistService llm = LlmProviderFactory.create(config.llm())) {
                result = new GenerationService(
                        config, new SqliteCacheSupply(dictStore, new CacheStore(db),
                                new ConfirmedStore(db)), llm).generate(form);
            }
            JSON.writeValue(output.toFile(), result);
            System.out.println("生成成功: 表 " + result.tableName + "，字段 " + result.fields.size()
                    + " 个，待登记 " + result.pendingRegistration.size() + " 项 → " + output);
        } catch (GenerationException e) {
            writeError(output, e.code(), e.getMessage());
            System.err.println("生成失败 [" + e.code() + "] " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            writeError(output, "ENGINE_ERROR", e.getMessage());
            System.err.println("引擎错误: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void runConfirm(Map<String, String> opts, Path configDir, Path dataDir) {
        Path input = Path.of(required(opts, "--input"));
        try {
            EngineConfig config = RulesLoader.load(configDir);
            Path db = EngineDatabase.initialize(dataDir);
            DictStore dictStore = new DictStore(db);
            ConfirmInput confirmInput = JSON.readValue(input.toFile(), ConfirmInput.class);
            int count = new ConfirmedService(config, new ConfirmedStore(db), dictStore)
                    .confirm(confirmInput);
            System.out.println("已确认候选登记完成: 系统 " + confirmInput.owningSystem + "，共 "
                    + count + " 个字段（同上下文下次生成直接复用）");
        } catch (GenerationException e) {
            System.err.println("登记失败 [" + e.code() + "] " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("引擎错误: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void runRefreshSchema(Map<String, String> opts, Path dataDir) {
        String system = required(opts, "--system");
        String jdbc = required(opts, "--jdbc");
        String user = required(opts, "--user");
        String password = opts.getOrDefault("--password", "");
        try {
            Path db = EngineDatabase.initialize(dataDir);
            int count = SchemaCacheRefresher.refresh(jdbc, user, password, system,
                    new CacheStore(db));
            System.out.println("Schema 缓存拉取完成: 系统 " + system + "，共 " + count + " 个字段");
        } catch (Exception e) {
            System.err.println("Schema 缓存拉取失败: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * 离线 COMMENT 规范化：确定性清洗优先、LLM 兜底、按批限流；
     * 结果独立落库 normalized_comment，结束后按键回填当前 Schema 缓存。
     */
    private static void runNormalizeComments(Map<String, String> opts, Path configDir,
                                             Path dataDir) {
        String system = required(opts, "--system");
        int batchSize = Integer.parseInt(opts.getOrDefault("--batch-size", "20"));
        long batchInterval = Long.parseLong(opts.getOrDefault("--batch-interval-ms", "1000"));
        try {
            EngineConfig config = RulesLoader.load(configDir);
            Path db = EngineDatabase.initialize(dataDir);
            try (LlmAssistService llm = LlmProviderFactory.create(config.llm())) {
                var report = new NormalizeCommentService(new CacheStore(db),
                        new NormalizedCommentStore(db), llm)
                        .normalize(system, batchSize, batchInterval);
                System.out.println(report.summaryLine());
            }
        } catch (Exception e) {
            System.err.println("COMMENT 规范化失败: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void runServe(Map<String, String> opts, Path configDir, Path dataDir) {
        try {
            EngineConfig config = RulesLoader.load(configDir);
            EngineSettings settings = EngineSettings.load(configDir.resolve("engine.yaml"));
            Path db = EngineDatabase.initialize(dataDir);
            DictStore dictStore = new DictStore(db);
            DictImportReport report = DictImporter.importAll(
                    configDir.resolve("dict"), dictStore, config.typeMapping());
            if (report.totalItems() > 0 || report.hasConflicts()) {
                System.out.println(report.summaryLine());
            }
            if (report.hasConflicts()) {
                System.err.println("字典导入冲突，服务未启动: " + report.conflicts);
                System.exit(1);
            }
            CacheStore cacheStore = new CacheStore(db);
            ConfirmedStore confirmedStore = new ConfirmedStore(db);
            // 主密钥经启动参数注入（部署脚本持有；缺省回退环境变量；不出现在任何日志）
            String masterKey = opts.getOrDefault("--master-key",
                    System.getenv("DBAGENT_MASTER_KEY"));
            HttpServer server = new EngineHttp(settings, config,
                    new SqliteCacheSupply(dictStore, cacheStore, confirmedStore), cacheStore,
                    confirmedStore, dictStore, LlmProviderFactory.create(config.llm()),
                    masterKey).start();
            System.out.println("引擎 HTTP 服务已启动: http://" + server.getAddress().getHostString()
                    + ":" + server.getAddress().getPort()
                    + "（POST /generate-ddl, /confirm, /refresh-schema；GET /health）");
            Thread.currentThread().join();
        } catch (Exception e) {
            System.err.println("服务启动失败: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void writeError(Path output, String code, String message) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", Map.of("code", code, "message", message == null ? "" : message));
            JSON.writeValue(output.toFile(), body);
        } catch (Exception ignore) {
            // 输出文件写失败时仅保留 stderr 提示
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length && !args[i + 1].startsWith("--")) {
                opts.put(args[i], args[i + 1]);
            }
        }
        return opts;
    }

    private static String required(Map<String, String> opts, String key) {
        String v = opts.get(key);
        if (v == null) {
            System.err.println("缺少参数 " + key);
            usage();
            System.exit(2);
        }
        return v;
    }

    private static void usage() {
        System.err.println("""
                用法:
                  java -jar engine.jar generate --input form.json --output result.json [--config config] [--data data]
                  java -jar engine.jar confirm --input confirm.json [--config config] [--data data]
                  java -jar engine.jar serve [--config config] [--data data] [--master-key 主密钥]
                  java -jar engine.jar refresh-schema --system 系统标识 --jdbc jdbc:mysql://host:3306/db --user 账号 [--password 口令]
                  java -jar engine.jar normalize-comments --system 系统标识 [--config config] [--data data] [--batch-size 20] [--batch-interval-ms 1000]
                """);
    }
}
