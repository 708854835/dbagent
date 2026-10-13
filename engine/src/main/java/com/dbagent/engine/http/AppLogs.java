package com.dbagent.engine.http;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 引擎日志缝：审计日志 logs/audit.log（append-only）与运行日志 logs/engine.log 分离。
 * 审计行四元组：时间 | NotesID | 动作 | 目标系统 | 结果；
 * 运行日志承载 WARN/ERROR 等运维事件。两处均不得写入密码、完整连接串、Token 值与主密钥。
 */
public final class AppLogs {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final Path auditFile;
    private final Path engineFile;

    public AppLogs(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("日志目录创建失败: " + dir, e);
        }
        this.auditFile = dir.resolve("audit.log");
        this.engineFile = dir.resolve("engine.log");
    }

    /** 审计事件（append-only）：时间/NotesID/动作/目标系统/结果。 */
    public synchronized void audit(String notesId, String action, String targetSystem, String result) {
        append(auditFile, LocalDateTime.now().format(TS)
                + " | " + safe(notesId) + " | " + safe(action)
                + " | " + safe(targetSystem) + " | " + safe(result));
    }

    public synchronized void info(String message) {
        append(engineFile, LocalDateTime.now().format(TS) + " INFO  " + message);
    }

    public synchronized void warn(String message) {
        append(engineFile, LocalDateTime.now().format(TS) + " WARN  " + message);
    }

    /** 运行错误（异常类名+消息，调用方保证不含敏感信息）。 */
    public synchronized void error(String message) {
        append(engineFile, LocalDateTime.now().format(TS) + " ERROR " + message);
    }

    public Path auditFile() {
        return auditFile;
    }

    public Path engineFile() {
        return engineFile;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static void append(Path file, String line) {
        try {
            Files.writeString(file, line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("日志写入失败: " + file, e);
        }
    }
}
