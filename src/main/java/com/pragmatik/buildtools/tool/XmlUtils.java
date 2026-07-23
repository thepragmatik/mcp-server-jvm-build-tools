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
package com.pragmatik.buildtools.tool;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight XML utilities for build-file parsing.
 * <p>
 * Extracted from {@code PomAnalyzer} and {@code DependencyService} to eliminate
 * duplicate {@code extractTag} / {@code extractAllTags} implementations.
 * Pure string-based (no DOM/SAX) — suitable for build-file metadata extraction.
 */
public final class XmlUtils {

    private XmlUtils() {
        // utility class
    }

    /**
     * Extract the text content of the first occurrence of {@code <tagName>...</tagName>}.
     *
     * @param xml     the XML string (may be null)
     * @param tagName the tag name without angle brackets
     * @return the text content, or null if the tag is not found
     */
    public static String extractTag(String xml, String tagName) {
        if (xml == null) return null;
        String openTag = "<" + tagName + ">";
        String closeTag = "</" + tagName + ">";
        int start = xml.indexOf(openTag);
        if (start < 0) return null;
        start += openTag.length();
        int end = xml.indexOf(closeTag, start);
        if (end < 0) return null;
        return xml.substring(start, end).trim();
    }

    /**
     * Extract the text content of all occurrences of {@code <tagName>...</tagName>}.
     *
     * @param xml     the XML string (may be null)
     * @param tagName the tag name without angle brackets
     * @return a list of text values, empty if none found
     */
    public static List<String> extractAllTags(String xml, String tagName) {
        List<String> values = new ArrayList<>();
        if (xml == null) return values;
        String openTag = "<" + tagName + ">";
        String closeTag = "</" + tagName + ">";
        int pos = 0;
        while (true) {
            int start = xml.indexOf(openTag, pos);
            if (start < 0) break;
            start += openTag.length();
            int end = xml.indexOf(closeTag, start);
            if (end < 0) break;
            values.add(xml.substring(start, end).trim());
            pos = end + closeTag.length();
        }
        return values;
    }
}
