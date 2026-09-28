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
package com.pragmatik.buildtools.build;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** Bounded, offline structural validation of a Maven POM. */
final class PomXmlValidator {

    private static final int MAX_POM_BYTES = 1_048_576;
    private static final String MAVEN_NAMESPACE = "http://maven.apache.org/POM/4.0.0";

    private PomXmlValidator() {}

    static List<Map<String, Object>> validate(Path pomXml) {
        byte[] xml;
        try (InputStream input = Files.newInputStream(pomXml)) {
            xml = input.readNBytes(MAX_POM_BYTES + 1);
        } catch (IOException e) {
            return List.of(issue("ERROR", "Cannot read pom.xml", null));
        }
        if (xml.length > MAX_POM_BYTES) {
            return List.of(issue("ERROR", "pom.xml is too large to validate", "Reduce pom.xml to 1 MiB or less"));
        }
        DocumentBuilder builder;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
        } catch (ParserConfigurationException | RuntimeException | javax.xml.parsers.FactoryConfigurationError e) {
            return List.of(issue("ERROR", "Secure XML parser is unavailable", null));
        }

        Document document;
        try {
            document = builder.parse(new ByteArrayInputStream(xml));
        } catch (SAXException | IOException e) {
            return List.of(issue("ERROR", "Malformed pom.xml", "Correct XML structure and remove external entities"));
        }

        Element project = document.getDocumentElement();
        if (project == null || !named(project, "project")) {
            return List.of(issue("ERROR", "Missing <project> root element", "Add <project> root element"));
        }

        List<Map<String, Object>> issues = new ArrayList<>();
        Element parent = child(project, "parent");
        required(issues, project, "modelVersion", null);
        required(issues, project, "groupId", parent);
        required(issues, project, "artifactId", null);
        required(issues, project, "version", parent);
        checkDependencies(issues, child(project, "dependencies"));
        checkPlugins(issues, child(project, "build"));
        return issues;
    }

    private static void required(List<Map<String, Object>> issues, Element project, String name, Element parent) {
        if (hasText(child(project, name)) || (parent != null && hasText(child(parent, name)))) return;
        issues.add(issue("ERROR", "Missing required element: <" + name + ">", "Add <" + name + "> to the project"));
    }

    private static void checkDependencies(List<Map<String, Object>> issues, Element dependencies) {
        if (dependencies == null) return;
        Set<String> seen = new HashSet<>();
        boolean duplicate = false;
        for (Element dependency : children(dependencies, "dependency")) {
            String group = value(child(dependency, "groupId"));
            String artifact = value(child(dependency, "artifactId"));
            if (group == null || artifact == null) continue;
            if (!seen.add(group + "\u0000" + artifact)) duplicate = true;
        }
        if (duplicate) {
            issues.add(issue("WARNING", "Duplicate dependency declaration", "Remove repeated <dependency> entries"));
        }
    }

    private static void checkPlugins(List<Map<String, Object>> issues, Element build) {
        if (build == null) return;
        Map<String, Set<String>> versions = new HashMap<>();
        collectPlugins(versions, child(build, "plugins"));
        Element pluginManagement = child(build, "pluginManagement");
        if (pluginManagement != null) collectPlugins(versions, child(pluginManagement, "plugins"));
        if (versions.values().stream().anyMatch(values -> values.size() > 1)) {
            issues.add(issue(
                    "WARNING", "Inconsistent plugin versions", "Use <pluginManagement> to centralize plugin versions"));
        }
    }

    private static void collectPlugins(Map<String, Set<String>> versions, Element plugins) {
        if (plugins == null) return;
        for (Element plugin : children(plugins, "plugin")) {
            String artifact = value(child(plugin, "artifactId"));
            String version = value(child(plugin, "version"));
            if (artifact == null || version == null) continue;
            String group = value(child(plugin, "groupId"));
            String key = (group == null ? "org.apache.maven.plugins" : group) + "\u0000" + artifact;
            versions.computeIfAbsent(key, ignored -> new HashSet<>()).add(version);
        }
    }

    private static boolean hasText(Element element) {
        return value(element) != null;
    }

    private static String value(Element element) {
        if (element == null) return null;
        String value = element.getTextContent().strip();
        return value.isEmpty() ? null : value;
    }

    private static Element child(Element parent, String name) {
        if (parent == null) return null;
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && named(element, name)) return element;
        }
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        if (parent == null) return result;
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && named(element, name)) result.add(element);
        }
        return result;
    }

    private static boolean named(Element element, String name) {
        String localName = element.getLocalName();
        String namespace = element.getNamespaceURI();
        return name.equals(localName == null ? element.getTagName() : localName)
                && (namespace == null || namespace.isEmpty() || MAVEN_NAMESPACE.equals(namespace));
    }

    private static Map<String, Object> issue(String severity, String message, String suggestion) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("severity", severity);
        issue.put("path", "pom.xml");
        issue.put("message", message);
        if (suggestion != null) issue.put("suggestion", suggestion);
        return issue;
    }
}
