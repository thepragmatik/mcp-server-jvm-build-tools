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

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-Sent Events (SSE) controller for streaming build events
 * to MCP clients and dashboards.
 * <p>
 * Provides real-time build event streaming following the MCP
 * streamable HTTP transport pattern. Clients connect to receive
 * build start, progress, completion, and error events.
 */
@RestController
@RequestMapping("/mcp/build-events")
public class BuildEventController {

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /**
     * Subscribe to build event stream. Returns SSE emitter that
     * stays open for the configured timeout (default 30 minutes).
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L); // 30 min timeout
        emitters.add(emitter);

        // Send initial connected event
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data("{\"status\":\"connected\",\"timestamp\":" + System.currentTimeMillis() + "}"));
        } catch (IOException e) {
            emitters.remove(emitter);
            emitter.completeWithError(e);
            return emitter;
        }

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));

        return emitter;
    }

    /**
     * Send a build event to all connected SSE subscribers.
     */
    void broadcast(String eventName, String data) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (IOException e) {
                emitters.remove(emitter);
                emitter.completeWithError(e);
            }
        }
    }

    /**
     * Get current subscriber count.
     */
    @GetMapping("/subscribers")
    public String subscriberCount() {
        return JsonUtils.toJson(Map.of("subscribers", emitters.size(), "endpoint", "/mcp/build-events/stream"));
    }
}
