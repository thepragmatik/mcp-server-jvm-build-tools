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
package com.pragmatik.buildtools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class MavenService {

    private static final String MAVEN_EXECUTION_PROMPT = """
            You are an assistant designed to translate natural language commands into safe, validated Maven build commands. Follow these rules precisely:
            
            0. **Pre-requisites**
               Ensure that the following are satisfied:
               - Maven home is already defined or specified by the user, otherwise, prompt the user for Maven home path.
               - The user must specify a project base directory where the Maven commands are to be executed, otherwise, prompt the user for project base directory.
            
            1. **Allowed Maven Commands Only:**
               Only translate commands that correspond to the following Maven operations:
               - clean
               - compile
               - test
               - package
               - install
               - deploy
               - validate
               If the input includes any command outside this list, respond with:
               "Error: Unsupported Maven command. Allowed commands are: clean, compile, test, package, install, deploy, validate."
               If the user issues a command that maps to directly executing a Maven plugin goal, refer above that you must only execute the Maven commands listed above.
            
            2. **Parameter and Flag Validation:**
               - Allow only alphanumeric characters, dashes (`-`), and underscores (`_`) for any additional parameters or flags.
               - If any parameter contains suspicious or disallowed characters, respond with:
                 "Error: Invalid parameter detected. Please check your input and try again."
               - Any arguments passed to the Maven command that needs to be in quotes must not be escaped.
            
            3. **Ambiguity and Clarification:**
               - If the natural language command is ambiguous or could map to multiple Maven commands, ask for clarification.
               Example: "Could you please specify whether you want to run tests or compile the project?"
               - Do not execute any command until the input is fully clarified.
            
            4. **No Execution of Non-Maven Operations:**
               - Do not translate or execute any input that might invoke system commands or other build tools.
               - Always return the intended Maven command as text without executing it.
            
            5. **Context Assurance:**
               - Before translating, confirm that the project context is correctly set (e.g., a valid Maven project directory is assumed).
               - If the context is unclear, prompt the user:
                 "Please confirm that you are in a Maven project directory."
            
            6. **Error Logging and Safe Fallback:**
               - If an unexpected input is encountered, log the input for review and respond with:
                 "Error: Command not recognized. Please rephrase your request according to the allowed commands."
            
            Your output should consist solely of the final validated Maven command in plain text, or an error/clarification message as described above.
            """;

    @Tool(name = "get_maven_version", description = "Gets the version for Apache Maven")
    public String version() {
        System.setProperty("maven.multiModuleProjectDirectory", new java.io.File(".").getAbsolutePath());
        return MavenInvoker.executeUsingMavenEmbedder(new String[]{"--version"}, ".");
    }

    @Tool(name = "execute_maven_command", description = MAVEN_EXECUTION_PROMPT)
    public String executeCommand(String mavenHome, String projectDir, String command) {
        String finalResult;
        if (mavenHome != null) {
            Path path = Path.of(mavenHome);
            if (!Files.exists(path) || !Files.isDirectory(path)) {
                throw new IllegalArgumentException("Invalid maven home directory: " + mavenHome);
            }
            System.setProperty("maven.home", mavenHome);
        } else {
            throw new IllegalArgumentException("Maven home cannot be null.");
        }

        String currentProjectDirectory;
        if (projectDir != null) {
            Path path = Path.of(projectDir);
            if (!Files.exists(path) || !Files.isDirectory(path)) {
                throw new IllegalArgumentException("The specified project directory '%s' does not exist.".formatted(projectDir));
            }
            currentProjectDirectory = projectDir;
            System.setProperty("maven.multiModuleProjectDirectory", projectDir);
        } else {
            throw new IllegalArgumentException("Maven project directory cannot be null.");
        }

        finalResult = MavenInvoker.executeCommandUsingMavenInvoker(mavenHome, MavenInvoker.getCommands(command), currentProjectDirectory);
        return finalResult;
    }

}
