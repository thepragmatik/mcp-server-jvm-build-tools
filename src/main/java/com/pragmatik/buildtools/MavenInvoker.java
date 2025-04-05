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

import org.apache.maven.cli.MavenCli;
import org.apache.maven.shared.invoker.*;

import java.io.*;
import java.util.Arrays;

public class MavenInvoker {

    static String executeCommandUsingMavenInvoker(String mavenHome, String[] commands, String currentProjectDirectory) {
        InvocationRequest request = new DefaultInvocationRequest();
        request.setInputStream(InputStream.nullInputStream());
        request.setBaseDirectory(new File(currentProjectDirectory));
        request.addArgs(Arrays.asList(commands));

        Invoker invoker = new DefaultInvoker();
        invoker.setWorkingDirectory(new File(currentProjectDirectory));
        invoker.setMavenHome(new File(mavenHome));

        StringBuilder output = new StringBuilder();
        StringBuilder errors = new StringBuilder();

        request.setOutputHandler(s -> output.append(s).append(System.lineSeparator()));
        request.setErrorHandler(s -> errors.append(s).append(System.lineSeparator()));

        String finalResult;
        try {
            InvocationResult result = invoker.execute(request);
            if (invocationResultedInError(result)) {
                if (result.getExecutionException() != null) {
                    result.getExecutionException().printStackTrace();
                }
                finalResult = errors.toString();
                throw new RuntimeException(finalResult);
            } else {
                finalResult = output.toString();
            }
        } catch (MavenInvocationException e) {
            finalResult = "Unable to invoke Maven command: " + e.getMessage();
            throw new RuntimeException(finalResult);
        }

        return finalResult;
    }

    static String executeUsingMavenEmbedder(String[] command, String currentProjectDirectory) {
        String finalResult;
        StringBuilder output = new StringBuilder();
        StringBuilder errors = new StringBuilder();

        OutputStream outputStream = new OutputStream() {
            @Override
            public void write(int b) {
                output.append((char) b);
            }
        };

        OutputStream errorStream = new OutputStream() {

            @Override
            public void write(int b) throws IOException {
                errors.append((char) b);
            }
        };

        PrintStream outPrintStream = new PrintStream(outputStream);
        PrintStream errPrintStream = new PrintStream(errorStream);

        MavenCli mavenCli = new MavenCli();

        int exitCode = mavenCli.doMain(command, currentProjectDirectory, outPrintStream, errPrintStream);

        if (exitCode != 0) {
            finalResult = errors.toString();
        } else {
            finalResult = output.toString();
        }
        return finalResult;
    }

    static String[] getCommands(String command) {
        var cmd = command;

        if (command.startsWith("mvn ")) {
            cmd = cmd.substring("mvn ".length()).trim();
        }
        return cmd.split("\\s");
    }

    static boolean invocationResultedInError(InvocationResult result) {
        return result.getExitCode() != 0;
    }
}
