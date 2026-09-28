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
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Opens a bounded build file relative to an allowed root's live directory handle. */
public final class AnchoredProjectFileReader {

    private AnchoredProjectFileReader() {}

    public static byte[] read(Path project, String filename, int maxBytes) throws IOException {
        Path root = project.getRoot();
        if (root == null || maxBytes < 1 || filename.contains("/") || filename.contains("\\")) {
            throw new IOException("Invalid project file request");
        }
        Path relativeProject = root.relativize(project);
        for (Path segment : relativeProject) {
            if (".".equals(segment.toString()) || "..".equals(segment.toString())) {
                throw new IOException("Invalid project path component");
            }
        }
        try (DirectoryStream<Path> rootStream = Files.newDirectoryStream(root)) {
            if (!(rootStream instanceof SecureDirectoryStream<?>)) {
                throw new UnsupportedOperationException("Race-free project file access is unavailable");
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
                try (var channel = directory.newByteChannel(
                                Path.of(filename), Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                        var input = Channels.newInputStream(channel)) {
                    return input.readNBytes(maxBytes + 1);
                }
            } finally {
                for (int i = children.size() - 1; i >= 0; i--) {
                    children.get(i).close();
                }
            }
        }
    }
}
