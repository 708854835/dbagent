package com.dbagent.engine.dict;

import com.dbagent.engine.cache.CacheSupply;
import com.dbagent.engine.db.DictStore;
import com.dbagent.engine.rules.TypeMappingRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 字典快照导入：扫描 config/dict/ 下全部 JSON，多文件全量合并、整体替换语义。
 * 口述记法归一化为标准记法；数据格式/英文名称非法的条目带"格式非法"标记照常入库；
 * 同编号不同内容 → 整个导入事务回滚（不写库，旧快照保留），报告列出冲突编号。
 */
public final class DictImporter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ENGLISH_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");
    private static final int ENGLISH_NAME_MAX_CHARS = 30;

    private DictImporter() {
    }

    public static DictImportReport importAll(Path dictDir, DictStore store,
                                             TypeMappingRules typeMapping) {
        DictImportReport report = new DictImportReport();
        List<Path> files = listJsonFiles(dictDir);
        if (files.isEmpty()) {
            return report;
        }

        Map<String, CacheSupply.DictItem> byId = new LinkedHashMap<>();
        for (Path file : files) {
            int total = 0;
            int illegal = 0;
            for (JsonNode node : readItems(file)) {
                total++;
                CacheSupply.DictItem item = toItem(node, typeMapping);
                if (item.formatIllegal()) {
                    illegal++;
                }
                CacheSupply.DictItem existing = byId.putIfAbsent(item.id(), item);
                if (existing != null && !existing.equals(item)) {
                    if (!report.conflicts.contains(item.id())) {
                        report.conflicts.add(item.id());
                    }
                }
            }
            report.files.add(new DictImportReport.FileStat(
                    file.getFileName().toString(), total, illegal));
        }

        if (report.hasConflicts()) {
            // 整个导入事务回滚：不写库，旧快照保留
            report.success = false;
            return report;
        }
        store.replaceAll(byId.values());
        report.imported = byId.size();
        return report;
    }

    private static List<Path> listJsonFiles(Path dictDir) {
        if (!Files.isDirectory(dictDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dictDir)) {
            return stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".json"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("字典目录读取失败: " + dictDir, e);
        }
    }

    private static List<JsonNode> readItems(Path file) {
        try {
            JsonNode root = JSON.readTree(file.toFile());
            JsonNode items = root.isArray() ? root : root.path("items");
            List<JsonNode> result = new ArrayList<>();
            if (items.isArray()) {
                items.forEach(result::add);
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("字典文件解析失败: " + file, e);
        }
    }

    /** JSON 节点 → 字典项：记法归一化 + 合法性校验（非法带标记照常入库）。 */
    static CacheSupply.DictItem toItem(JsonNode node, TypeMappingRules typeMapping) {
        String id = text(node, "id");
        String chineseName = text(node, "chineseName");
        String englishName = text(node, "englishName");
        String importance = text(node, "importance");
        String dataType = text(node, "dataType");
        String dataFormat = typeMapping.normalizeFormat(text(node, "dataFormat"));
        String codeRule = text(node, "codeRule");

        List<CacheSupply.Code> codeList = new ArrayList<>();
        JsonNode codes = node.path("codeList");
        if (codes.isArray()) {
            for (JsonNode c : codes) {
                codeList.add(new CacheSupply.Code(text(c, "code"), text(c, "name")));
            }
        }

        boolean illegal = !isEnglishNameLegal(englishName)
                || !isKnownDataType(typeMapping, dataType)
                || !typeMapping.isFormatLegal(dataType, dataFormat);

        return new CacheSupply.DictItem(id, chineseName, englishName, importance, dataType,
                dataFormat, codeList, codeRule, illegal);
    }

    /** 英文名称格式校验：下划线分割、≤30 字符（大小写由输出端统一转小写，不判非法）。 */
    private static boolean isEnglishNameLegal(String englishName) {
        return englishName != null
                && englishName.length() <= ENGLISH_NAME_MAX_CHARS
                && ENGLISH_NAME.matcher(englishName).matches();
    }

    private static boolean isKnownDataType(TypeMappingRules typeMapping, String dataType) {
        return dataType != null && typeMapping.types.containsKey(dataType);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}
