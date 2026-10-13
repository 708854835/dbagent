package com.dbagent.engine.dict;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字典快照导入报告：按文件汇总条目数/格式非法数，冲突编号列表。
 */
public final class DictImportReport {

    /** 单文件汇总。 */
    public record FileStat(String file, int total, int illegal) {
    }

    public final List<FileStat> files = new ArrayList<>();
    public final List<String> conflicts = new ArrayList<>();
    public int imported;
    public boolean success = true;

    public boolean hasConflicts() {
        return !conflicts.isEmpty();
    }

    public boolean success() {
        return success;
    }

    public int totalIllegal() {
        return files.stream().mapToInt(FileStat::illegal).sum();
    }

    public int totalItems() {
        return files.stream().mapToInt(FileStat::total).sum();
    }

    public String summaryLine() {
        if (hasConflicts()) {
            return "字典导入失败: 同编号不同内容冲突 " + conflicts.size() + " 项 "
                    + conflicts + "（已回滚，保留旧快照）";
        }
        return "字典导入完成: " + files.size() + " 个文件，共 " + imported + " 条"
                + (totalIllegal() > 0 ? "，其中格式非法 " + totalIllegal() + " 条" : "");
    }

    /** 逐文件明细（供数据管理员向数据标准部门反馈数据质量）。 */
    public Map<String, Object> toDetailMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", success);
        m.put("imported", imported);
        m.put("files", files);
        m.put("conflicts", conflicts);
        return m;
    }
}
