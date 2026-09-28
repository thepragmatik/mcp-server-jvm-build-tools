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

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Parses bounded Maven metadata without DTDs, external entities, or free-form parser errors. */
final class MavenMetadataParser {
    private static final int MAX_VERSIONS = 4096;

    private MavenMetadataParser() {}

    static Metadata parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void warning(SAXParseException e) throws SAXException {
                    throw e;
                }

                @Override
                public void error(SAXParseException e) throws SAXException {
                    throw e;
                }

                @Override
                public void fatalError(SAXParseException e) throws SAXException {
                    throw e;
                }
            });
            Element root = builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
            if (root == null || !"metadata".equals(root.getTagName())) {
                throw new IllegalArgumentException("Invalid Maven metadata");
            }
            Element versioning = child(root, "versioning");
            if (versioning == null) {
                throw new IllegalArgumentException("Invalid Maven metadata");
            }
            List<String> versions = new ArrayList<>();
            Element versionsElement = child(versioning, "versions");
            if (versionsElement != null) {
                for (Node node = versionsElement.getFirstChild(); node != null; node = node.getNextSibling()) {
                    if (node instanceof Element element && "version".equals(element.getTagName())) {
                        if (versions.size() == MAX_VERSIONS) {
                            throw new IllegalArgumentException("Maven metadata has too many versions");
                        }
                        versions.add(element.getTextContent().trim());
                    }
                }
            }
            return new Metadata(
                    text(versioning, "latest"),
                    text(versioning, "release"),
                    text(versioning, "lastUpdated"),
                    List.copyOf(versions));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalArgumentException("Invalid Maven metadata");
        }
    }

    private static Element child(Element parent, String name) {
        if (parent == null) return null;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getTagName())) return element;
        }
        return null;
    }

    private static String text(Element parent, String name) {
        Element element = child(parent, name);
        return element == null ? null : element.getTextContent().trim();
    }

    record Metadata(String latest, String release, String lastUpdated, List<String> versions) {}
}
