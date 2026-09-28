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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;

/** Image-build guard for race-free configuration file traversal. */
class SecureDirectoryStreamProbe {
    public static void main(String[] args) throws Exception {
        try (var root = Files.newDirectoryStream(Path.of("/"))) {
            if (!(root instanceof SecureDirectoryStream<?>)) {
                throw new IllegalStateException("Secure directory access is unavailable");
            }
        }
        System.out.println("PASS secure directory access");
    }
}
