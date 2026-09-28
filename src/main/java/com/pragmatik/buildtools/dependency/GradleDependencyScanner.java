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
package com.pragmatik.buildtools.dependency;

import com.pragmatik.buildtools.dependency.security.CveLookupService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Extracts supported literal Gradle calls while keeping comments and quoted code inert. */
final class GradleDependencyScanner {
    private static final Set<String> CONFIGURATIONS =
            Set.of("implementation", "api", "compileOnly", "runtimeOnly", "testImplementation", "testRuntimeOnly");
    private static final int MAX_TOKENS = 100_000;

    private GradleDependencyScanner() {}

    static List<CveLookupService.PackageRef> parse(String script) {
        List<Token> tokens = tokenize(script);
        List<CveLookupService.PackageRef> packages = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.quoted() || !CONFIGURATIONS.contains(token.value())) continue;
            int argument = i + 1;
            boolean parenthesized =
                    argument < tokens.size() && "(".equals(tokens.get(argument).value());
            if (parenthesized) argument++;
            if (argument >= tokens.size() || !tokens.get(argument).quoted()) {
                throw new IncompleteDependencyScanException();
            }
            String[] coordinate = tokens.get(argument).value().split(":", -1);
            if (coordinate.length != 3) throw new IncompleteDependencyScanException();
            CveLookupService.PackageRef pkg =
                    new CveLookupService.PackageRef(coordinate[0], coordinate[1], coordinate[2]);
            if (!CveLookupService.supports(pkg)) throw new IncompleteDependencyScanException();
            if (parenthesized) {
                if (argument + 1 >= tokens.size()
                        || !")".equals(tokens.get(argument + 1).value())) {
                    throw new IncompleteDependencyScanException();
                }
            } else if (argument + 1 < tokens.size()
                    && "+".equals(tokens.get(argument + 1).value())) {
                throw new IncompleteDependencyScanException();
            }
            packages.add(pkg);
            if (packages.size() > CveLookupService.MAX_SCAN_PACKAGES) {
                throw new IncompleteDependencyScanException();
            }
            i = parenthesized ? argument + 1 : argument;
        }
        return List.copyOf(packages);
    }

    private record Token(String value, boolean quoted) {}

    private static List<Token> tokenize(String script) {
        List<Token> tokens = new ArrayList<>();
        for (int i = 0; i < script.length(); ) {
            char c = script.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '/' && i + 1 < script.length() && script.charAt(i + 1) == '/') {
                i += 2;
                while (i < script.length() && script.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < script.length() && script.charAt(i + 1) == '*') {
                int end = script.indexOf("*/", i + 2);
                if (end < 0) throw new IncompleteDependencyScanException();
                i = end + 2;
            } else if (c == '/' || c == '`') {
                // Slashy Groovy strings and Kotlin backtick identifiers need a richer lexer.
                // Reject them rather than mistake their contents for live dependencies.
                throw new IncompleteDependencyScanException();
            } else if (c == '\'' || c == '"') {
                boolean triple = i + 2 < script.length() && script.charAt(i + 1) == c && script.charAt(i + 2) == c;
                int start = i + (triple ? 3 : 1);
                StringBuilder value = new StringBuilder();
                i = start;
                boolean closed = false;
                while (i < script.length()) {
                    if (triple
                            && i + 2 < script.length()
                            && script.charAt(i) == c
                            && script.charAt(i + 1) == c
                            && script.charAt(i + 2) == c) {
                        i += 3;
                        closed = true;
                        break;
                    }
                    if (!triple && script.charAt(i) == c) {
                        i++;
                        closed = true;
                        break;
                    }
                    if (!triple && script.charAt(i) == '\\' && i + 1 < script.length()) {
                        i++;
                    }
                    value.append(script.charAt(i++));
                }
                if (!closed) throw new IncompleteDependencyScanException();
                tokens.add(new Token(value.toString(), true));
            } else if (Character.isJavaIdentifierStart(c)) {
                int start = i++;
                while (i < script.length() && Character.isJavaIdentifierPart(script.charAt(i))) i++;
                tokens.add(new Token(script.substring(start, i), false));
            } else {
                tokens.add(new Token(String.valueOf(c), false));
                i++;
            }
            if (tokens.size() > MAX_TOKENS) throw new IncompleteDependencyScanException();
        }
        return tokens;
    }
}
