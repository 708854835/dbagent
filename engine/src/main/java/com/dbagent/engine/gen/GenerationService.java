package com.dbagent.engine.gen;

import com.dbagent.engine.cache.CacheSupply;
import com.dbagent.engine.ddl.DdlBuilder;
import com.dbagent.engine.decision.Badge;
import com.dbagent.engine.decision.DecisionChain;
import com.dbagent.engine.decision.FieldDecision;
import com.dbagent.engine.dict.AbbreviationDictionary;
import com.dbagent.engine.form.FormField;
import com.dbagent.engine.form.FormInput;
import com.dbagent.engine.rules.AuditFieldRules;
import com.dbagent.engine.rules.EngineConfig;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 生成服务缝（主缝）：表单 JSON 进 → 生成结果（DDL/徽章/待登记清单/索引建议占位）出。
 * CLI/HTTP 均为薄壳，直调此缝。
 */
public final class GenerationService {

    private final EngineConfig config;
    private final DecisionChain decisionChain;

    public GenerationService(EngineConfig config, CacheSupply cacheSupply) {
        this.config = config;
        this.decisionChain = new DecisionChain(
                cacheSupply, config.fieldAbbrev(), config.typeMapping(), config.naming());
    }

    public GenerationResult generate(FormInput form) {
        validate(form);
        GenerationResult result = new GenerationResult();
        List<String> warnings = result.warnings;

        // 表名推导：t_ 前缀 + 表名专用缩写词典
        result.tableName = deriveTableName(form.tableChineseName.trim(), warnings, result);

        // 字段三级决策
        List<FieldDecision> decisions = new ArrayList<>();
        for (FormField f : form.fields) {
            decisions.add(decisionChain.decide(f));
        }
        for (FieldDecision d : decisions) {
            if (!d.isUsable()) {
                warnings.add("字段「" + d.chineseName() + "」需人工复核: " + d.note()
                        + "（修正前不进入 DDL）");
            }
        }

        // 主键：用户勾选优先，否则自动补齐自增 id
        List<String> pkColumns = new ArrayList<>();
        for (int i = 0; i < form.fields.size(); i++) {
            if (form.fields.get(i).isPrimaryKey()) {
                FieldDecision d = decisions.get(i);
                if (!d.isUsable()) {
                    throw new GenerationException("VALIDATION_FAILED",
                            "主键字段「" + d.chineseName() + "」需人工复核，无法作为主键");
                }
                pkColumns.add(d.englishName());
            }
        }
        boolean autoPk = pkColumns.isEmpty();
        if (autoPk) {
            pkColumns.add(config.constraints().autoPrimaryKey.name);
        }

        // 审计字段：按中文名别名清单去重
        Set<String> userNames = new LinkedHashSet<>();
        for (FormField f : form.fields) {
            userNames.add(f.chineseName.trim());
        }
        List<AuditFieldRules.AuditField> audits = new ArrayList<>();
        for (AuditFieldRules.AuditField a : config.auditFields().fields) {
            if (a.aliasList.stream().noneMatch(userNames::contains)) {
                audits.add(a);
            }
        }

        // 字段数（含预估补齐数）超上限 → 预览期硬拦截
        int totalFields = decisions.size() + (autoPk ? 1 : 0) + audits.size();
        int maxFields = config.constraints().maxFields;
        if (totalFields > maxFields) {
            throw new GenerationException("FIELD_COUNT_EXCEEDED",
                    "单表字段数（含预估补齐数）共 " + totalFields + " 个，超过 " + maxFields
                            + " 上限，请垂直拆表");
        }

        // DDL 列组装
        List<DdlBuilder.Column> columns = new ArrayList<>();
        if (autoPk) {
            var apk = config.constraints().autoPrimaryKey;
            columns.add(new DdlBuilder.Column(apk.name, apk.physicalType, null,
                    "AUTO_INCREMENT", apk.comment));
        }
        for (FieldDecision d : decisions) {
            if (!d.isUsable()) {
                continue;
            }
            columns.add(new DdlBuilder.Column(d.englishName(), d.physicalType(),
                    defaultValueFor(d), null, d.chineseName()));
        }
        for (AuditFieldRules.AuditField a : audits) {
            columns.add(new DdlBuilder.Column(a.englishName, a.physicalType,
                    a.defaultValue, a.extra, a.comment));
        }

        String tableComment = form.description == null || form.description.isBlank()
                ? form.tableChineseName.trim()
                : form.tableChineseName.trim() + " " + form.description.trim();
        result.ddl = new DdlBuilder().build(result.tableName, tableComment, columns,
                pkColumns, config.constraints().engine, config.constraints().charset);

        // 逐字段视图 + 待登记清单 + 索引建议占位
        for (FieldDecision d : decisions) {
            GenerationResult.FieldView view = new GenerationResult.FieldView();
            view.chineseName = d.chineseName();
            view.englishName = d.englishName();
            view.dataType = d.dataType();
            view.physicalType = d.physicalType();
            view.badge = d.badge().display();
            view.note = d.note();
            result.fields.add(view);

            if (d.badge() == Badge.DERIVED) {
                GenerationResult.PendingItem item = new GenerationResult.PendingItem();
                item.chineseName = d.chineseName();
                item.englishName = d.englishName();
                item.importance = "自治级";
                item.dataType = d.dataType();
                FormField source = findField(form, d.chineseName());
                item.dataFormat = config.typeMapping().dataFormat(
                        d.dataType(), source.length, source.precision);
                result.pendingRegistration.add(item);
            }
        }
        GenerationResult.IndexSuggestion pkIndex = new GenerationResult.IndexSuggestion();
        pkIndex.name = "PRIMARY";
        pkIndex.columns = List.copyOf(pkColumns);
        pkIndex.fixed = true;
        result.indexSuggestions.add(pkIndex);

        return result;
    }

    private String deriveTableName(String tableChineseName, List<String> warnings,
                                   GenerationResult result) {
        AbbreviationDictionary.Derivation derivation = config.tableAbbrev().derive(tableChineseName);
        if (!derivation.fullyCovered()) {
            // LLM 辅助接入前占位：表名整体标需人工复核
            result.tableNameNeedsReview = true;
            warnings.add("表名缩写词典未覆盖: " + String.join("/", derivation.uncovered())
                    + "，表名需人工复核");
        }
        if (derivation.parts().isEmpty()) {
            throw new GenerationException("VALIDATION_FAILED",
                    "表名无法按缩写词典推导，请手工指定表名");
        }
        // 表名简称连写不加下划线（如"同业借款合同台账"→ t_tyjkht），字段名才用下划线分割
        String tableName = config.naming().table.prefix + String.join("", derivation.parts());
        int bytes = tableName.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > config.naming().table.absoluteMaxBytes) {
            throw new GenerationException("TABLE_NAME_TOO_LONG",
                    "表名 " + tableName + " 共 " + bytes + " 字节，超过 "
                            + config.naming().table.absoluteMaxBytes + " 字节上限，请手工指定表名");
        }
        if (bytes > config.naming().table.preferredMaxBytes) {
            warnings.add("表名 " + tableName + " 共 " + bytes + " 字节，超过 "
                    + config.naming().table.preferredMaxBytes + " 字节，建议缩短");
        }
        return tableName;
    }

    /** NOT NULL + 默认值按规则补齐：推导/贯标按数据类型取默认值；复用按物理类型推断。 */
    private String defaultValueFor(FieldDecision d) {
        if (d.dataType() != null) {
            return config.typeMapping().defaultValue(d.dataType());
        }
        String physical = d.physicalType().toUpperCase();
        if (physical.startsWith("VARCHAR") || physical.startsWith("CHAR")
                || physical.startsWith("TEXT")) {
            return "''";
        }
        if (physical.startsWith("DECIMAL") || physical.startsWith("INT")
                || physical.startsWith("BIGINT") || physical.startsWith("TINYINT")) {
            return "0";
        }
        return null;
    }

    private void validate(FormInput form) {
        if (form == null || form.tableChineseName == null || form.tableChineseName.isBlank()) {
            throw new GenerationException("VALIDATION_FAILED", "表中文名为空（阻断级）");
        }
        if (form.fields == null || form.fields.isEmpty()) {
            throw new GenerationException("VALIDATION_FAILED", "字段清单为空（阻断级）");
        }
        for (FormField f : form.fields) {
            if (f.chineseName == null || f.chineseName.isBlank()) {
                throw new GenerationException("VALIDATION_FAILED", "存在字段中文名为空（阻断级）");
            }
        }
    }

    private static FormField findField(FormInput form, String chineseName) {
        return form.fields.stream()
                .filter(f -> f.chineseName.trim().equals(chineseName))
                .findFirst()
                .orElseThrow();
    }
}
