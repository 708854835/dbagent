package com.dbagent.engine.cache;

import com.dbagent.engine.db.ConfirmedStore;
import com.dbagent.engine.db.DictStore;
import com.dbagent.engine.form.ConfirmInput;
import com.dbagent.engine.form.FormField;
import com.dbagent.engine.form.FormInput;
import com.dbagent.engine.gen.GenerationException;
import com.dbagent.engine.rules.EngineConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 已确认候选登记服务（CLI/HTTP 薄壳共用）：校验 → 上下文指纹 → 落库（绑定当前字典快照）。
 * 确认后的定义在同上下文下次生成直接复用（决策链第①.⑤级），不再走 LLM。
 */
public final class ConfirmedService {

    private final EngineConfig config;
    private final ConfirmedStore confirmedStore;
    private final DictStore dictStore;

    public ConfirmedService(EngineConfig config, ConfirmedStore confirmedStore,
                            DictStore dictStore) {
        this.config = config;
        this.confirmedStore = confirmedStore;
        this.dictStore = dictStore;
    }

    /**
     * @return 登记条数
     * @throws GenerationException 输入非法（阻断级：表名/字段名缺失、确认字段不在表单内、
     *                             英文名不合规、数据类型未知）
     */
    public int confirm(ConfirmInput input) {
        if (input == null || input.tableChineseName == null || input.tableChineseName.isBlank()) {
            throw new GenerationException("VALIDATION_FAILED", "表中文名为空（阻断级）");
        }
        if (input.owningSystem == null || input.owningSystem.isBlank()) {
            throw new GenerationException("VALIDATION_FAILED", "归属系统为空（阻断级）");
        }
        if (input.fieldNames == null || input.fieldNames.isEmpty()) {
            throw new GenerationException("VALIDATION_FAILED", "表单字段中文名集合为空（阻断级）");
        }
        if (input.confirmed == null || input.confirmed.isEmpty()) {
            throw new GenerationException("VALIDATION_FAILED", "确认字段清单为空（阻断级）");
        }
        String fingerprint = fingerprint(input.owningSystem, input.tableChineseName,
                input.fieldNames);
        String snapshot = dictStore.currentSnapshot();
        for (ConfirmInput.ConfirmedField f : input.confirmed) {
            validateField(input, f);
            confirmedStore.save(input.owningSystem, f.chineseName.trim(), fingerprint,
                    f.englishName.trim().toLowerCase(java.util.Locale.ROOT),
                    f.dataType, f.physicalType.trim(), f.codeList, snapshot);
        }
        return input.confirmed.size();
    }

    private void validateField(ConfirmInput input, ConfirmInput.ConfirmedField f) {
        if (f.chineseName == null || f.chineseName.isBlank()
                || !input.fieldNames.contains(f.chineseName.trim())) {
            throw new GenerationException("VALIDATION_FAILED",
                    "确认字段不在表单字段集合内: " + f.chineseName);
        }
        if (f.englishName == null || f.englishName.isBlank()) {
            throw new GenerationException("VALIDATION_FAILED",
                    "确认字段英文名为空: " + f.chineseName);
        }
        String name = f.englishName.trim().toLowerCase(java.util.Locale.ROOT);
        if (name.length() > config.naming().field.maxChars
                || config.naming().field.reservedWords.contains(name)
                || !Pattern.matches(config.naming().field.pattern, name)) {
            throw new GenerationException("VALIDATION_FAILED",
                    "确认字段英文名不合规: " + name);
        }
        if (f.dataType == null || config.typeMapping().types.get(f.dataType) == null) {
            throw new GenerationException("VALIDATION_FAILED",
                    "确认字段数据类型未知: " + f.chineseName + " → " + f.dataType);
        }
        if (f.physicalType == null || f.physicalType.isBlank()) {
            throw new GenerationException("VALIDATION_FAILED",
                    "确认字段物理类型为空: " + f.chineseName);
        }
    }

    /** 上下文指纹：归属系统 + 表中文名 + 字段中文名集合（排序）的 SHA-256。 */
    public static String fingerprint(String owningSystem, String tableChineseName,
                                     List<String> fieldNames) {
        String canonical = owningSystem.trim() + "\u0001" + tableChineseName.trim() + "\u0001"
                + String.join("\u0001", new TreeSet<>(fieldNames));
        return DictStore.sha256Hex(canonical);
    }

    /** 生成场景的指纹计算：表单无归属系统时返回 null（跳过确认缓存级）。 */
    public static String fingerprintOf(FormInput form) {
        if (form.owningSystem == null || form.owningSystem.isBlank()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (FormField f : form.fields) {
            names.add(f.chineseName.trim());
        }
        return fingerprint(form.owningSystem, form.tableChineseName, names);
    }
}
