package com.dbagent.engine.ddl;

import java.util.List;
import java.util.stream.Collectors;

/**
 * MySQL/TXSQL DDL 组装：NOT NULL+默认值、逐字段 COMMENT、主键、ENGINE/字符集明确写出、无外键。
 */
public final class DdlBuilder {

    public record Column(String name, String physicalType, String defaultValue,
                         String extra, String comment) {
    }

    public String build(String tableName, String tableComment, List<Column> columns,
                        List<String> primaryKey, String engine, String charset) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE `").append(tableName).append("` (\n");
        String cols = columns.stream()
                .map(DdlBuilder::render)
                .collect(Collectors.joining(",\n"));
        sb.append(cols);
        String pk = primaryKey.stream()
                .map(c -> "`" + c + "`")
                .collect(Collectors.joining(", "));
        sb.append(",\n  PRIMARY KEY (").append(pk).append(')');
        sb.append("\n) ENGINE=").append(engine)
                .append(" DEFAULT CHARSET=").append(charset)
                .append(" COMMENT '").append(escape(tableComment)).append("';\n");
        return sb.toString();
    }

    private static String render(Column c) {
        StringBuilder sb = new StringBuilder();
        sb.append("  `").append(c.name()).append("` ").append(c.physicalType())
                .append(" NOT NULL");
        if (c.defaultValue() != null) {
            sb.append(" DEFAULT ").append(c.defaultValue());
        }
        if (c.extra() != null) {
            sb.append(' ').append(c.extra());
        }
        sb.append(" COMMENT '").append(escape(c.comment())).append('\'');
        return sb.toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("'", "''");
    }
}
