/*
 *
 *  Copyright 2026 The mcp-server-jvm-build-tools contributors
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
package com.pragmatik.buildtools.security;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** The fixed, public projection of a build-tool listing returned to an MCP client. */
record PublicBuildToolListing(List<String> tools) {
    PublicBuildToolListing {
        tools = List.copyOf(tools);
    }

    static PublicBuildToolListing from(JsonNode parsed, String boundedOutput) {
        // A callback may JSON-quote a String result. The egress policy has already
        // parsed it; a direct or malformed result uses the same bounded raw fallback.
        String listing = parsed != null && parsed.isTextual() ? parsed.asText() : boundedOutput;
        boolean maven = false;
        boolean gradle = false;
        boolean sbt = false;
        Iterator<String> lines = listing.lines().iterator();
        while (lines.hasNext() && !(maven && gradle && sbt)) {
            String line = lines.next();
            if (line.startsWith("maven:")) {
                maven = true;
            } else if (line.startsWith("gradle:")) {
                gradle = true;
            } else if (line.startsWith("sbt:")) {
                sbt = true;
            }
        }
        List<String> names = new ArrayList<>(3);
        if (maven) {
            names.add("maven");
        }
        if (gradle) {
            names.add("gradle");
        }
        if (sbt) {
            names.add("sbt");
        }
        return new PublicBuildToolListing(names);
    }
}
