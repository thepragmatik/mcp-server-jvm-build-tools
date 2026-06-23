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
 * Provides real-time build event streaming. Clients connect to receive
 * build start, progress, completion, and error events.
 *
 * <h2>Relationship to the MCP transport (2026-07-28 RC)</h2>
 * This is a <b>supplementary telemetry feed</b>, <i>not</i> the MCP protocol
 * transport. The RC removes protocol-level sessions and SSE-stream resumability
 * (the {@code Last-Event-ID} header and SSE event IDs, SEP-2575), and reclassifies
 * the HTTP+SSE protocol transport as Deprecated (SEP-2596). Accordingly:
 * <ul>
 *   <li>This channel deliberately carries <b>no event IDs</b> and offers <b>no
 *       resumability / redelivery</b> — it is a live, best-effort firehose. A
 *       dropped connection simply re-subscribes; no in-flight protocol request is
 *       lost because no MCP request is carried here.</li>
 *   <li>It holds <b>no protocol-level session state</b> and pins nothing to a
 *       connection, so it stays compatible with the stateless transport and with
 *       round-robin load balancers (any replica can serve it).</li>
 *   <li>Cross-call build state uses explicit, server-minted handles instead — see
 *       {@link AsyncBuildService}'s {@code taskId} (SEP-2567).</li>
 * </ul>
 * <b>Decision:</b> retained as an optional, opt-in dashboard/telemetry stream that
 * is independent of MCP protocol semantics. It is not used to deliver MCP JSON-RPC
 * responses and must not be relied upon for protocol message delivery.
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
