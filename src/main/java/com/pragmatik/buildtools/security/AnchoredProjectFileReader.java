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
package com.pragmatik.buildtools.security;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Opens bounded build files relative to one live, no-follow project directory handle. */
public final class AnchoredProjectFileReader {
    private AnchoredProjectFileReader() {}

    static final class UnsupportedProviderException extends UnsupportedOperationException {
        private UnsupportedProviderException() {
            super("Race-free project file access is unavailable");
        }
    }

    public static ProjectDirectory open(Path project) throws IOException {
        Path root = project.getRoot();
        if (root == null) {
            throw new IOException("Invalid project path");
        }
        Path relativeProject = root.relativize(project);
        for (Path segment : relativeProject) {
            if (".".equals(segment.toString()) || "..".equals(segment.toString())) {
                throw new IOException("Invalid project path component");
            }
        }

        DirectoryStream<Path> rootStream = Files.newDirectoryStream(root);
        if (!(rootStream instanceof SecureDirectoryStream<?>)) {
            rootStream.close();
            throw new UnsupportedProviderException();
        }
        @SuppressWarnings("unchecked")
        SecureDirectoryStream<Path> rootDirectory = (SecureDirectoryStream<Path>) rootStream;
        SecureDirectoryStream<Path> directory = rootDirectory;
        List<SecureDirectoryStream<Path>> children = new ArrayList<>();
        try {
            for (Path segment : relativeProject) {
                directory = directory.newDirectoryStream(segment, LinkOption.NOFOLLOW_LINKS);
                children.add(directory);
            }
            return new ProjectDirectory(rootDirectory, children, directory);
        } catch (IOException | RuntimeException e) {
            for (int i = children.size() - 1; i >= 0; i--) {
                try {
                    children.get(i).close();
                } catch (IOException closeFailure) {
                    e.addSuppressed(closeFailure);
                }
            }
            try {
                rootDirectory.close();
            } catch (IOException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    /** Preserves the authorized directory identity across marker checks and file reads. */
    public static final class ProjectDirectory implements AutoCloseable {
        private final SecureDirectoryStream<Path> root;
        private final List<SecureDirectoryStream<Path>> children;
        private final SecureDirectoryStream<Path> directory;

        private ProjectDirectory(
                SecureDirectoryStream<Path> root,
                List<SecureDirectoryStream<Path>> children,
                SecureDirectoryStream<Path> directory) {
            this.root = root;
            this.children = children;
            this.directory = directory;
        }

        /** Rejects a present marker that is a symlink or non-regular file. */
        public void requireSafeMarker(String filename) throws IOException {
            requireSafeMarker(directory, filename);
        }

        /** Inspects a nested marker without resolving an intermediate symlink. */
        public void requireSafeNestedMarker(String parent, String filename) throws IOException {
            BasicFileAttributes parentAttributes = attributesIfPresent(directory, parent);
            if (parentAttributes == null) return;
            // An unrelated regular file named "project" cannot contain the nested sbt marker.
            if (parentAttributes.isRegularFile()) return;
            if (!parentAttributes.isDirectory()) throw new IOException("Unsafe build marker directory");
            try (SecureDirectoryStream<Path> nested =
                    directory.newDirectoryStream(Path.of(parent), LinkOption.NOFOLLOW_LINKS)) {
                requireSafeMarker(nested, filename);
            }
        }

        private static void requireSafeMarker(SecureDirectoryStream<Path> parent, String filename) throws IOException {
            BasicFileAttributes attributes = attributesIfPresent(parent, filename);
            if (attributes != null && !attributes.isRegularFile()) {
                throw new IOException("Unsafe build marker");
            }
        }

        private static BasicFileAttributes attributesIfPresent(SecureDirectoryStream<Path> parent, String name)
                throws IOException {
            if (name == null
                    || name.isEmpty()
                    || ".".equals(name)
                    || "..".equals(name)
                    || name.contains("/")
                    || name.contains("\\")) {
                throw new IOException("Invalid build marker name");
            }
            BasicFileAttributeView view =
                    parent.getFileAttributeView(Path.of(name), BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) throw new IOException("Build marker attributes are unavailable");
            try {
                return view.readAttributes();
            } catch (NoSuchFileException e) {
                return null;
            }
        }

        /** Returns null for an absent marker; symlinks and read failures throw. */
        public byte[] readIfPresent(String filename, int maxBytes) throws IOException {
            if (maxBytes < 1
                    || filename == null
                    || filename.isEmpty()
                    || ".".equals(filename)
                    || "..".equals(filename)
                    || filename.contains("/")
                    || filename.contains("\\")) {
                throw new IOException("Invalid project file request");
            }
            try (var channel = directory.newByteChannel(
                            Path.of(filename), Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                    var input = Channels.newInputStream(channel)) {
                return input.readNBytes(maxBytes + 1);
            } catch (NoSuchFileException e) {
                return null;
            }
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            for (int i = children.size() - 1; i >= 0; i--) {
                try {
                    children.get(i).close();
                } catch (IOException e) {
                    if (failure == null) failure = e;
                    else failure.addSuppressed(e);
                }
            }
            try {
                root.close();
            } catch (IOException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
            if (failure != null) throw failure;
        }
    }

    public static byte[] read(Path project, String filename, int maxBytes) throws IOException {
        try (ProjectDirectory directory = open(project)) {
            byte[] bytes = directory.readIfPresent(filename, maxBytes);
            if (bytes == null) throw new NoSuchFileException(filename);
            return bytes;
        }
    }
}
