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
package com.pragmatik.buildtools.transport;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Sends a raw HTTP request so tests can control the restricted Host header. */
final class RawMcpRequest {

    private RawMcpRequest() {}

    static int postStatus(int port, String host, String origin) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
        StringBuilder request = new StringBuilder("POST /mcp HTTP/1.1\r\n")
                .append("Host: ")
                .append(host)
                .append("\r\n")
                .append("Content-Type: application/json\r\n")
                .append("Accept: application/json, text/event-stream\r\n")
                .append("Content-Length: ")
                .append(body.getBytes(StandardCharsets.UTF_8).length)
                .append("\r\n")
                .append("Connection: close\r\n");
        if (origin != null) {
            request.append("Origin: ").append(origin).append("\r\n");
        }
        request.append("\r\n").append(body);

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            String status = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                    .readLine();
            return Integer.parseInt(status.split(" ")[1]);
        }
    }
}
