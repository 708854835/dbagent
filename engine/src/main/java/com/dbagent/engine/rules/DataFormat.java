package com.dbagent.engine.rules;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据格式记号解析（《数据库设计审核要求》§4）。
 * 支持：n6/an3/anc5（定长）、an..32/anc..128（最大）、16(2)（小数）、
 * YYYYMMDD、HHMMSS[.S...S]、YYYYMMDDHHMMSS / YYYYMMDDTHHMMSS[.S...S]。
 * 纯数字格式（如 "12"）不允许，解析返回 null。
 */
public record DataFormat(Kind kind, int length, boolean fixed, int intDigits, int fracDigits) {

    public enum Kind {
        CHAR_SEQ, DECIMAL, DATE, TIME, DATETIME
    }

    private static final Pattern CHAR_FIXED = Pattern.compile("^(anc|an|a|n)(\\d+)$");
    private static final Pattern CHAR_MAX = Pattern.compile("^(anc|an|a|n)\\.\\.(\\d+)$");
    private static final Pattern DECIMAL = Pattern.compile("^(\\d+)\\((\\d+)\\)$");
    private static final Pattern PURE_DIGITS = Pattern.compile("^\\d+$");
    private static final Pattern TIME = Pattern.compile("^HHMMSS(\\.S+)?$");
    private static final Pattern DATETIME = Pattern.compile("^(YYYYMMDDHHMMSS|YYYYMMDDTHHMMSS(\\.S+)?)$");

    /** 解析失败（含纯数字格式）返回 null。 */
    public static DataFormat parse(String format) {
        if (format == null || format.isBlank() || PURE_DIGITS.matcher(format).matches()) {
            return null;
        }
        String f = format.trim();
        if ("YYYYMMDD".equals(f)) {
            return new DataFormat(Kind.DATE, 0, true, 0, 0);
        }
        if (TIME.matcher(f).matches()) {
            return new DataFormat(Kind.TIME, 0, true, 0, 0);
        }
        if (DATETIME.matcher(f).matches()) {
            return new DataFormat(Kind.DATETIME, 0, true, 0, 0);
        }
        Matcher dm = DECIMAL.matcher(f);
        if (dm.matches()) {
            return new DataFormat(Kind.DECIMAL, 0, true,
                    Integer.parseInt(dm.group(1)), Integer.parseInt(dm.group(2)));
        }
        Matcher fm = CHAR_FIXED.matcher(f);
        if (fm.matches()) {
            return new DataFormat(Kind.CHAR_SEQ, Integer.parseInt(fm.group(2)), true, 0, 0);
        }
        Matcher mm = CHAR_MAX.matcher(f);
        if (mm.matches()) {
            return new DataFormat(Kind.CHAR_SEQ, Integer.parseInt(mm.group(2)), false, 0, 0);
        }
        return null;
    }
}
