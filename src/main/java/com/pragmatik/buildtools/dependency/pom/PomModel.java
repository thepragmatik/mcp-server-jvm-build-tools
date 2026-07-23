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
package com.pragmatik.buildtools.dependency.pom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal data model for POM analysis results.
 * <p>
 * Provides classification enums and record-like classes for representing
 * POM structure, dependency entries, and resolution results. These types
 * are consumed by {@link PomAnalyzer} and {@link PomDependencyResolver}
 * to build the structured JSON output returned by
 * {@code DependencyService#analyzePomDependencies}.
 */
public final class PomModel {

    private PomModel() {
        // utility class
    }

    /**
     * Classification of a dependency's version origin.
     */
    public enum Classification {
        /** Version explicitly declared in the project POM. */
        EXPLICIT,
        /** Version inherited from a {@code <dependencyManagement>} section. */
        MANAGED,
        /**
         * Explicit version that differs from the one declared in
         * {@code <dependencyManagement>} (an override).
         */
        OVERRIDE
    }

    /**
     * Represents a single dependency declaration extracted from a POM.
     */
    public static final class DependencyEntry {
        private final String groupId;
        private final String artifactId;
        private final String version;
        private final String scope;
        private final boolean optional;

        public DependencyEntry(String groupId, String artifactId, String version, String scope, boolean optional) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.scope = scope != null ? scope : "compile";
            this.optional = optional;
        }

        public String groupId() {
            return groupId;
        }

        public String artifactId() {
            return artifactId;
        }

        public String version() {
            return version;
        }

        public String scope() {
            return scope;
        }

        public boolean optional() {
            return optional;
        }

        @Override
        public String toString() {
            return groupId + ":" + artifactId + ":" + (version != null ? version : "?");
        }
    }

    /**
     * A fully resolved dependency with classification and provenance.
     */
    public static final class ResolvedDependency {
        private final String groupId;
        private final String artifactId;
        private final String version;
        private final String scope;
        private final boolean optional;
        private final Classification classification;
        private final String source;

        public ResolvedDependency(
                String groupId,
                String artifactId,
                String version,
                String scope,
                boolean optional,
                Classification classification,
                String source) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.scope = scope != null ? scope : "compile";
            this.optional = optional;
            this.classification = classification;
            this.source = source;
        }

        public String groupId() {
            return groupId;
        }

        public String artifactId() {
            return artifactId;
        }

        public String version() {
            return version;
        }

        public String scope() {
            return scope;
        }

        public boolean optional() {
            return optional;
        }

        public Classification classification() {
            return classification;
        }

        public String source() {
            return source;
        }

        /**
         * Serialize this resolved dependency to an ordered map suitable for JSON output.
         */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("groupId", groupId);
            map.put("artifactId", artifactId);
            if (version != null) {
                map.put("version", version);
            }
            map.put("scope", scope);
            if (optional) {
                map.put("optional", true);
            }
            map.put("classification", classification.name());
            map.put("source", source);
            return map;
        }
    }

    /**
     * Parsed information about a POM project.
     */
    public static final class PomInfo {
        private final String groupId;
        private final String artifactId;
        private final String version;
        private final String packaging;
        private final Map<String, String> parent;
        private final Map<String, String> properties;
        private final List<DependencyEntry> dependencies;
        private final List<DependencyEntry> managedDependencies;
        private final List<String> importedBoms;

        public PomInfo(
                String groupId,
                String artifactId,
                String version,
                String packaging,
                Map<String, String> parent,
                Map<String, String> properties,
                List<DependencyEntry> dependencies,
                List<DependencyEntry> managedDependencies,
                List<String> importedBoms) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.packaging = packaging != null ? packaging : "jar";
            this.parent = parent != null ? Map.copyOf(parent) : null;
            this.properties = properties != null ? Map.copyOf(properties) : Map.of();
            this.dependencies = dependencies != null ? List.copyOf(dependencies) : List.of();
            this.managedDependencies = managedDependencies != null ? List.copyOf(managedDependencies) : List.of();
            this.importedBoms = importedBoms != null ? List.copyOf(importedBoms) : List.of();
        }

        public String groupId() {
            return groupId;
        }

        public String artifactId() {
            return artifactId;
        }

        public String version() {
            return version;
        }

        public String packaging() {
            return packaging;
        }

        public Map<String, String> parent() {
            return parent;
        }

        public Map<String, String> properties() {
            return properties;
        }

        public List<DependencyEntry> dependencies() {
            return dependencies;
        }

        public List<DependencyEntry> managedDependencies() {
            return managedDependencies;
        }

        public List<String> importedBoms() {
            return importedBoms;
        }
    }

    /**
     * Holds the complete result of POM analysis including resolved deps,
     * parent chain, warnings, and property substitutions.
     */
    public static final class AnalysisResult {
        private final PomInfo project;
        private final List<ResolvedDependency> dependencies;
        private final List<String> parentChain;
        private final Map<String, String> propertySubstitutions;
        private final List<String> unresolvedProperties;
        private final List<String> warnings;

        public AnalysisResult(
                PomInfo project,
                List<ResolvedDependency> dependencies,
                List<String> parentChain,
                Map<String, String> propertySubstitutions,
                List<String> unresolvedProperties,
                List<String> warnings) {
            this.project = project;
            this.dependencies = List.copyOf(dependencies);
            this.parentChain = List.copyOf(parentChain);
            this.propertySubstitutions = Map.copyOf(propertySubstitutions);
            this.unresolvedProperties = List.copyOf(unresolvedProperties);
            this.warnings = List.copyOf(warnings);
        }

        public PomInfo project() {
            return project;
        }

        public List<ResolvedDependency> dependencies() {
            return dependencies;
        }

        public List<String> parentChain() {
            return parentChain;
        }

        public Map<String, String> propertySubstitutions() {
            return propertySubstitutions;
        }

        public List<String> unresolvedProperties() {
            return unresolvedProperties;
        }

        public List<String> warnings() {
            return warnings;
        }

        /**
         * Serialize the full analysis result to an ordered map for JSON output.
         */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();

            // project
            Map<String, Object> projectMap = new LinkedHashMap<>();
            projectMap.put("groupId", project.groupId());
            projectMap.put("artifactId", project.artifactId());
            if (project.version() != null) {
                projectMap.put("version", project.version());
            }
            projectMap.put("packaging", project.packaging());
            if (project.parent() != null && !project.parent().isEmpty()) {
                projectMap.put("parent", project.parent());
            }
            map.put("project", projectMap);

            // dependencies
            List<Map<String, Object>> depList = new ArrayList<>();
            for (ResolvedDependency dep : dependencies) {
                depList.add(dep.toMap());
            }
            map.put("dependencies", depList);

            // managedDependencies
            List<String> managedList = new ArrayList<>();
            for (DependencyEntry dep : project.managedDependencies()) {
                managedList.add(dep.toString());
            }
            map.put("managedDependencies", managedList);

            // importedBoms
            map.put("importedBoms", project.importedBoms());

            // parentChain
            map.put("parentChain", parentChain);

            // propertySubstitutions
            map.put("propertySubstitutions", propertySubstitutions);

            // unresolvedProperties
            map.put("unresolvedProperties", unresolvedProperties);

            // warnings
            map.put("warnings", warnings);

            return map;
        }
    }
}
