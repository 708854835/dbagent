package com.dbagent.engine.rules;

/**
 * 物理建表约束（constraints.yaml）。
 */
public class ConstraintRules {

    public String charset = "utf8mb4";
    public String engine = "InnoDB";
    public boolean foreignKeysAllowed = false;
    public int maxFields = 30;
    public int maxIndexes = 5;
    public AutoPrimaryKey autoPrimaryKey = new AutoPrimaryKey();

    public static class AutoPrimaryKey {
        public String name = "id";
        public String physicalType = "BIGINT UNSIGNED";
        public String comment = "自增主键";
    }
}
