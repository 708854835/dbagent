package com.dbagent.engine.rules;

import java.util.List;

/**
 * 命名规则（naming.yaml）。
 */
public class NamingRules {

    public Table table = new Table();
    public Field field = new Field();

    public static class Table {
        public String prefix = "t_";
        public int preferredMaxBytes = 32;
        public int absoluteMaxBytes = 64;
    }

    public static class Field {
        public int maxChars = 30;
        public String pattern = "^[a-z][a-z0-9_]*$";
        public List<String> reservedWords = List.of();
    }
}
