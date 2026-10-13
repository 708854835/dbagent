package com.dbagent.engine.cli;

import com.dbagent.engine.cache.SqliteCacheSupply;
import com.dbagent.engine.db.DictStore;
import com.dbagent.engine.db.EngineDatabase;
import com.dbagent.engine.dict.DictImportReport;
import com.dbagent.engine.dict.DictImporter;
import com.dbagent.engine.form.FormInput;
import com.dbagent.engine.gen.GenerationException;
import com.dbagent.engine.gen.GenerationResult;
import com.dbagent.engine.gen.GenerationService;
import com.dbagent.engine.rules.EngineConfig;
import com.dbagent.engine.rules.RulesLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 引擎 CLI：generate 子命令直调生成服务缝（免 Token、不走 HTTP）。
 *
 * <pre>java -jar engine.jar generate --input form.json --output result.json [--config config] [--data data]</pre>
 */
public final class EngineCli {

    private EngineCli() {
    }

    public static void main(String[] args) {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true,
                StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true,
                StandardCharsets.UTF_8));

        if (args.length == 0 || !"generate".equals(args[0])) {
            usage();
            System.exit(2);
        }

        Map<String, String> opts = parseArgs(args);
        Path input = Path.of(required(opts, "--input"));
        Path output = Path.of(required(opts, "--output"));
        Path configDir = Path.of(opts.getOrDefault("--config", "config"));
        Path dataDir = Path.of(opts.getOrDefault("--data", "data"));

        ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
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
                writeError(json, output, "DICT_IMPORT_CONFLICT", report.summaryLine());
                System.exit(1);
            }
            FormInput form = json.readValue(input.toFile(), FormInput.class);
            GenerationResult result = new GenerationService(config, new SqliteCacheSupply(dictStore))
                    .generate(form);
            json.writeValue(output.toFile(), result);
            System.out.println("生成成功: 表 " + result.tableName + "，字段 " + result.fields.size()
                    + " 个，待登记 " + result.pendingRegistration.size() + " 项 → " + output);
        } catch (GenerationException e) {
            writeError(json, output, e.code(), e.getMessage());
            System.err.println("生成失败 [" + e.code() + "] " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            writeError(json, output, "ENGINE_ERROR", e.getMessage());
            System.err.println("引擎错误: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void writeError(ObjectMapper json, Path output, String code, String message) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", Map.of("code", code, "message", message == null ? "" : message));
            json.writeValue(output.toFile(), body);
        } catch (Exception ignore) {
            // 输出文件写失败时仅保留 stderr 提示
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 1; i < args.length - 1; i++) {
            if (args[i].startsWith("--")) {
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
        System.err.println("用法: java -jar engine.jar generate --input form.json --output result.json"
                + " [--config config] [--data data]");
    }
}
