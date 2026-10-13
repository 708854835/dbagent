package com.dbagent.engine.dict;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 缩写词典：中文词 → 英文缩写。表名专用与字段专用两套独立维护。
 * 匹配规则：从左到右最长词匹配；空缩写表示该词在简称中省略。
 */
public final class AbbreviationDictionary {

    private static final Pattern ABBREV_PATTERN = Pattern.compile("[a-z][a-z0-9_]*");

    private final Map<String, String> entries;

    public AbbreviationDictionary(Map<String, String> entries) {
        // 最长键优先，保证最长匹配
        this.entries = new LinkedHashMap<>();
        entries.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, String>>comparingInt(e -> e.getKey().length()).reversed()
                        .thenComparing(Map.Entry::getKey))
                .forEach(e -> {
                    String word = e.getKey();
                    String abbrev = e.getValue() == null ? "" : e.getValue().trim();
                    if (word == null || word.isBlank()) {
                        throw new IllegalArgumentException("缩写词典存在空中文词");
                    }
                    if (!abbrev.isEmpty() && !ABBREV_PATTERN.matcher(abbrev).matches()) {
                        throw new IllegalArgumentException(
                                "缩写词典词条不合法（须为小写字母/数字/下划线）: " + word + " -> " + abbrev);
                    }
                    this.entries.put(word, abbrev);
                });
    }

    /**
     * 按最长匹配逐词切分并缩写。
     *
     * @return 推导结果：parts 为各词缩写（空缩写已剔除）；uncovered 为词典未覆盖的子串
     */
    public Derivation derive(String chinese) {
        List<String> parts = new ArrayList<>();
        List<String> uncovered = new ArrayList<>();
        String remaining = chinese.trim();
        while (!remaining.isEmpty()) {
            String matchedWord = null;
            String matchedAbbrev = null;
            for (Map.Entry<String, String> e : entries.entrySet()) {
                if (remaining.startsWith(e.getKey())) {
                    matchedWord = e.getKey();
                    matchedAbbrev = e.getValue();
                    break;
                }
            }
            if (matchedWord == null) {
                // 未覆盖：取到下一个可命中位置为止，整体记为未覆盖子串
                int next = nextMatchPos(remaining);
                uncovered.add(next < 0 ? remaining : remaining.substring(0, next));
                remaining = next < 0 ? "" : remaining.substring(next);
            } else {
                if (!matchedAbbrev.isEmpty()) {
                    parts.add(matchedAbbrev);
                }
                remaining = remaining.substring(matchedWord.length());
            }
        }
        return new Derivation(parts, uncovered);
    }

    private int nextMatchPos(String s) {
        for (int i = 1; i < s.length(); i++) {
            String sub = s.substring(i);
            for (String key : entries.keySet()) {
                if (sub.startsWith(key)) {
                    return i;
                }
            }
        }
        return -1;
    }

    public record Derivation(List<String> parts, List<String> uncovered) {

        public boolean fullyCovered() {
            return uncovered.isEmpty();
        }

        public String joined() {
            return String.join("_", parts);
        }
    }
}
