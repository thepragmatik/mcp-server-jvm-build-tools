# MCP Server - Build Tools

A brief overview of what the project does and its purpose.

## Table of Contents
- [Overview](#overview)
- [Prerequisites](#prerequisites)
- [Features](#features)
- [Installation](#installation)
- [Usage](#usage)
- [Contributing](#contributing)
- [License](#license)

## Overview
Describe the project, its goals, and any background context.
For example:
This project is to demonstrate an MCP server that enables users to interact with Maven (for now) using natural language.
It aims to showcase how build tools can be exposed via MCP and offer a natural, intuitive way of building projects.

## Prerequisites
List the software and tools needed before installing the project:
- Java 21 or later
- Maven
- MCP Client (Claude, Goose etc)
- optionally, a local LLM if you are using MCP client like Goose

## Features
- **Simple to use:** Set this up with any MCP client and LLM of your choice to invoke builds using natural language. No need to remember plugins, goals and arguments! and
- **Supports Apache Maven:** For now it only support Apache Maven. We might extend it for other build tools if there is interest.


## Installation (For Claude Desktop)
1. **Add this MCP server to Claude:**

   Locate the `claude_desktop_config.json` file under Claude installation directory
 
   For MacOs, this is at:

    `~/Library/Application\ Support/Claude/claude_desktop_config.json`

    Then, add something like the following to it (replace the placeholders):
    ```
    {
        "mcpServers": {
            "maven-tools": {
                "command": "<path-to-java-home-directory>/bin/java",
                "args": [
                    "-jar",
                    "-Dmaven.home=<path-to-maven-home-directory>",
                    "<path-to-the-mcp-maven-server.jar>"
                ]
            }
        }
    }
    ```

2. You may need to restart the Claude client. This will start the MCP server for build tools.

3. You should now be ready to interact with Maven via the MCP server using the Claude desktop. Go ahead submit a task in natural language to build a project.

## Docker

A multi-stage Docker image is available. Build it:

```bash
docker build -t mcp-server-jvm-build-tools .
```

Run the server (stdio-based, so stdin must be connected):

```bash
docker run -i --rm \
  -v /path/to/maven/home:/opt/maven \
  -v /path/to/project:/workspace \
  -e MAVEN_HOME=/opt/maven \
  mcp-server-jvm-build-tools
```

The image bundles a minimal Maven installation at `/usr/share/java/maven-3`,
but for production use you should volume-mount your actual Maven home and
override `MAVEN_HOME`.

### Docker with Claude Desktop

If running via Claude Desktop, configure the MCP server entry to use Docker:

```json
{
    "mcpServers": {
        "maven-tools": {
            "command": "docker",
            "args": [
                "run", "-i", "--rm",
                "-v", "/path/to/maven:/opt/maven",
                "-e", "MAVEN_HOME=/opt/maven",
                "mcp-server-jvm-build-tools"
            ]
        }
    }
}
```

## Release Process

### Versioning

This project uses semantic versioning (`MAJOR.MINOR.PATCH`).
The version is defined in `pom.xml`:

```xml
<version>0.0.3-SNAPSHOT</version>
```

### Cutting a Release

1. **Ensure all tests pass on main:**

   ```bash
   mvn verify
   ```

2. **Prepare and perform the release** (uses `maven-release-plugin`):

   ```bash
   mvn release:prepare release:perform
   ```

   This will:
   - Remove `-SNAPSHOT` from the version
   - Create a git tag (`vX.Y.Z`)
   - Bump the version to the next snapshot
   - Push changes and tags to GitHub

3. **The tag push triggers the release workflow** (`.github/workflows/maven-publish.yml`),
   which runs tests, packages the JAR, and creates a GitHub Release.

### Manual Release

If you prefer to release manually:

```bash
# 1. Update version in pom.xml (remove -SNAPSHOT)
# 2. Commit and tag
git tag -a v0.0.3 -m "Release v0.0.3"
git push origin v0.0.3
# 3. GitHub Actions picks up the tag and creates the release
```

After the release, bump the version back to a snapshot:

```bash
# Update pom.xml version to next development iteration, e.g. 0.0.4-SNAPSHOT
git commit -am "[release] Bump to next snapshot"
git push
```


## Contributing
1. Use Github Issues to open issues or feature requests.

## License 
This repo is licensed under the Apache License. See the [LICENSE](LICENSE) file for details