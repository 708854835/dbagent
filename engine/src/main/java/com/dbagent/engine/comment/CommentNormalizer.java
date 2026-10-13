package com.dbagent.engine.comment;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * COMMENT 确定性清洗（离线规范化第一优先，不触 LLM）：
 * 全/半角归一、空白归一（中文间空格删除）、分隔符归一（，；、：（）→ 半角）、
 * 常见模式提取（尾部代码清单 "名称: 01-男, 02-女" / "名称(01=男;02=女)"）。
 * 无法确定性解析时返回 null，交 LLM 兜底转标准 JSON。
 */
public final class CommentNormalizer {

    /** 纯名称：中文/字母/数字开头，允许空白、括号、_ - / 连接 */
    private static final Pattern NAME =
            Pattern.compile("^[\\p{IsHan}A-Za-z0-9][\\p{IsHan}A-Za-z0-9 _()/-]{0,63}$");
    /** 代码清单项起点：key-value 结构（key 1-8 位字母数字） */
    private static final Pattern ITEM_START = Pattern.compile("[A-Za-z0-9]{1,8}\\s*[-=]\\S");
    /** 单个代码清单项：key[-=]value */
    private static final Pattern ITEM = Pattern.compile("^([A-Za-z0-9]{1,8})\\s*[-=]\\s*(.+)$");

    private CommentNormalizer() {
    }

    /**
     * @param name  规范化业务中文名（必填）
     * @param codes 识别到的代码清单（key→含义）；无代码清单为 null
     */
    public record Normalized(String name, Map<String, String> codes) {
    }

    /** 确定性清洗；无法解析返回 null（交 LLM 兜底）。 */
    public static Normalized clean(String raw) {
        if (raw == null) {
            return null;
        }
        String s = normalizeSeparators(raw);
        if (s.isEmpty()) {
            return null;
        }
        // 常见模式：尾部代码清单（"性别: 01-男, 02-女" / "性别(01=男;02=女)"）
        Matcher lead = ITEM_START.matcher(s);
        if (lead.find()) {
            String name = s.substring(0, lead.start()).replaceAll("[(:,;/\\s-]+$", "").trim();
            String rest = s.substring(lead.start()).replaceAll("[)\\s]+$", "").trim();
            Map<String, String> codes = parseItems(rest);
            if (codes != null && NAME.matcher(name).matches()) {
                return new Normalized(name, codes);
            }
            return null;
        }
        // 纯名称
        if (NAME.matcher(s).matches()) {
            return new Normalized(s, null);
        }
        return null;
    }

    /** 全/半角归一 + 空白归一（中文间空格删除）+ 分隔符归一。 */
    static String normalizeSeparators(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '　') {
                sb.append(' ');
            } else if (c >= '！' && c <= '～') {
                sb.append((char) (c - 0xFEE0));
            } else if (c == '、') {
                sb.append(',');
            } else {
                sb.append(c);
            }
        }
        String s = sb.toString().replaceAll("\\s+", " ").trim();
        // 中文之间的空格为录入噪音，删除（"客户 编号" → "客户编号"）
        return s.replaceAll("(?<=[\\p{IsHan}]) (?=[\\p{IsHan}])", "");
    }

    /** 解析代码清单体：各项以 , ; / 分隔，每项必须匹配 key[-=]value；任一项不合规返回 null。 */
    private static Map<String, String> parseItems(String rest) {
        Map<String, String> codes = new LinkedHashMap<>();
        for (String token : rest.split("[,;/]+")) {
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            Matcher m = ITEM.matcher(t);
            if (!m.matches()) {
                return null;
            }
            codes.put(m.group(1), m.group(2).trim());
        }
        return codes.isEmpty() ? null : codes;
    }
}
