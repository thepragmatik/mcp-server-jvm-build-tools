/*
 *
 *  Copyright 2025 Rahul Thakur
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.pragmatik.buildtools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Structured audit logger for MCP tool invocations.
 *
 * Records every tool call with timestamp, tool name, caller identity,
 * and authorization decision. Writes to a configurable audit log file
 * (default: ~/.buildtools/audit.log) as newline-delimited JSON.
 *
 * Designed to satisfy OWASP MCP06 (Insufficient Logging and Monitoring)
 * and NSA guidance for per-request logging.
 */
public final class ToolAuditLogger {

    private static final Logger log = LoggerFactory.getLogger(ToolAuditLogger.class);

    private static final DateTimeFormatter ISO_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX")
                    .withZone(ZoneId.of("UTC"));

    private final Path auditLogPath;
    private final boolean enabled;

    public ToolAuditLogger(String auditLogPathStr, boolean enabled) {
        this.enabled = enabled;
        if (auditLogPathStr != null && !auditLogPathStr.isBlank()) {
            this.auditLogPath = Paths.get(auditLogPathStr);
        } else {
            String home = System.getProperty("user.home");
            this.auditLogPath = Paths.get(home, ".buildtools", "audit.log");
        }
    }

    public void record(String toolName, String caller, List<String> scopes,
                       boolean authorized, long durationMs) {
        if (!enabled) return;

        String scopesStr = "null";
        if (scopes != null && !scopes.isEmpty()) {
            scopesStr = "\"" + String.join(",", scopes) + "\"";
        }

        String event = String.format(
                "{\"timestamp\":\"%s\",\"tool\":\"%s\",\"caller\":\"%s\",\"scopes\":%s,\"authorized\":%s,\"durationMs\":%d}",
                ISO_FORMAT.format(Instant.now()),
                toolName,
                caller != null ? caller : "unknown",
                scopesStr,
                authorized,
                durationMs);

        try {
            Files.createDirectories(auditLogPath.getParent());
            Files.writeString(auditLogPath, event + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Failed to write audit log entry to {}: {}", auditLogPath, e.getMessage());
        }
    }

    public List<String> tail(int count) {
        if (!enabled || !Files.exists(auditLogPath)) return List.of();
        try {
            List<String> lines = Files.readAllLines(auditLogPath);
            int from = Math.max(0, lines.size() - count);
            return lines.subList(from, lines.size());
        } catch (IOException e) {
            log.warn("Failed to read audit log: {}", e.getMessage());
            return List.of();
        }
    }

    public boolean isEnabled() { return enabled; }
    public String getAuditLogPath() { return auditLogPath.toString(); }
}
