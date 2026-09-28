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
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** Offline extraction of literal, project-level Maven dependencies. Input is already size-bounded. */
final class MavenPomScanParser {
    private static final String MAVEN_NAMESPACE = "http://maven.apache.org/POM/4.0.0";

    private MavenPomScanParser() {}

    static List<CveLookupService.PackageRef> parse(String xml) {
        Document document;
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
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            document = builder.parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException
                | SAXException
                | IOException
                | RuntimeException
                | javax.xml.parsers.FactoryConfigurationError e) {
            throw new IncompleteDependencyScanException();
        }

        Element project = document.getDocumentElement();
        if (document.getDoctype() != null || !named(project, "project")) {
            throw new IncompleteDependencyScanException();
        }
        Element dependencies = uniqueChild(project, "dependencies");
        if (dependencies == null) return List.of();

        List<CveLookupService.PackageRef> packages = new ArrayList<>();
        NodeList nodes = dependencies.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element dependency)) continue;
            if (!named(dependency, "dependency")) throw new IncompleteDependencyScanException();
            String group = requiredText(dependency, "groupId");
            String artifact = requiredText(dependency, "artifactId");
            String version = requiredText(dependency, "version");
            CveLookupService.PackageRef pkg = new CveLookupService.PackageRef(group, artifact, version);
            if (!CveLookupService.supports(pkg)) throw new IncompleteDependencyScanException();
            packages.add(pkg);
        }
        return List.copyOf(packages);
    }

    private static String requiredText(Element parent, String name) {
        Element child = uniqueChild(parent, name);
        if (child == null) throw new IncompleteDependencyScanException();
        for (Node node = child.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element) throw new IncompleteDependencyScanException();
        }
        String value = child.getTextContent().strip();
        if (value.isEmpty() || value.contains("${") || value.contains("$")) {
            throw new IncompleteDependencyScanException();
        }
        return value;
    }

    private static Element uniqueChild(Element parent, String name) {
        Element found = null;
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element && named(element, name)) {
                if (found != null) throw new IncompleteDependencyScanException();
                found = element;
            }
        }
        return found;
    }

    private static boolean named(Element element, String name) {
        if (element == null) return false;
        String local = element.getLocalName();
        String namespace = element.getNamespaceURI();
        return name.equals(local == null ? element.getTagName() : local)
                && (namespace == null || namespace.isEmpty() || MAVEN_NAMESPACE.equals(namespace));
    }
}
