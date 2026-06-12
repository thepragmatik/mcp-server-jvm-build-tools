#!/usr/bin/env bash
# =============================================================================
# mcp-server-jvm-build-tools — CLI Launcher
#
# Auto-discovers Java, Maven, Gradle, and SBT installations and starts the
# MCP server JAR with appropriate configuration.
#
# Usage:
#   ./scripts/launcher.sh                     # stdio mode (default)
#   ./scripts/launcher.sh --http              # Streamable HTTP mode
#   ./scripts/launcher.sh --port 8080         # custom port (HTTP mode)
#   ./scripts/launcher.sh --help              # show usage
#
# Environment variables:
#   JAVA_HOME      — Java installation directory (auto-detected if unset)
#   MAVEN_HOME     — Maven installation directory (required for Maven builds)
#   GRADLE_HOME    — Gradle installation directory (optional)
#   SBT_HOME       — SBT installation directory (optional)
#   SERVER_PORT    — HTTP port (default: 8080)
#   MCP_OPTS       — Additional JVM options
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JAR_PATH="$PROJECT_DIR/target/mcp-server-jvm-build-tools.jar"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }

usage() {
    cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Options:
  --http              Enable Streamable HTTP transport (default: stdio)
  --port PORT         HTTP port (default: 8080, effective only with --http)
  --help              Show this help message

Environment:
  JAVA_HOME           Java installation directory
  MAVEN_HOME          Maven installation directory (required for Maven builds)
  GRADLE_HOME         Gradle installation directory (optional)
  SBT_HOME            SBT installation directory (optional)
  SERVER_PORT         HTTP port (default: 8080)
  MCP_OPTS            Additional JVM options
EOF
    exit 0
}

HTTP_MODE=false; SERVER_PORT="${SERVER_PORT:-8080}"
while [[ $# -gt 0 ]]; do
    case "$1" in --http) HTTP_MODE=true; shift ;; --port) SERVER_PORT="$2"; shift 2 ;; --help) usage ;; *) err "Unknown: $1"; usage ;; esac
done

echo -e "${CYAN}"
echo "  __  __ ____ ____    Server for JVM Build Tools"
echo " |  \/  |  _ \___ \    MCP - Maven . Gradle . SBT"
echo " | |\/| | |_) |__) |"
echo " | |  | |  __/  __/   Launcher v1.0"
echo " |_|  |_|_|  |_____|"
echo -e "${NC}"

find_java() {
    if [[ -n "${JAVA_HOME:-}" ]]; then JAVA="$JAVA_HOME/bin/java"
    elif command -v java &>/dev/null; then JAVA="java"
    else err "Java not found. Set JAVA_HOME or add java to PATH."; exit 1; fi
    if [[ ! -x "$JAVA" ]]; then JAVA=$(command -v java) || { err "Java not executable"; exit 1; }; fi
    JAVA_VERSION=$("$JAVA" -version 2>&1 | head -1 | sed 's/.*version "\([^"]*\)".*/\1/')
    info "Java: $JAVA -> $JAVA_VERSION"
}
find_java
JAVA_MAJOR=$(echo "$JAVA_VERSION" | cut -d. -f1)
[[ "$JAVA_MAJOR" -lt 21 ]] && { err "Java 21+ required (found $JAVA_VERSION)"; exit 1; }
ok "Java $JAVA_VERSION"

find_maven() {
    if [[ -n "${MAVEN_HOME:-}" ]]; then MAVEN="$MAVEN_HOME/bin/mvn"
    elif [[ -f "$PROJECT_DIR/mvnw" ]]; then MAVEN="$PROJECT_DIR/mvnw"
    elif command -v mvn &>/dev/null; then MAVEN="mvn"
    else warn "Maven not found. Set MAVEN_HOME."; return; fi
    ok "Maven: ${MAVEN:-not found}"
}
find_maven

find_gradle() {
    if [[ -n "${GRADLE_HOME:-}" ]]; then GRADLE="$GRADLE_HOME/bin/gradle"
    elif [[ -f "$PROJECT_DIR/gradlew" ]]; then GRADLE="$PROJECT_DIR/gradlew"
    elif command -v gradle &>/dev/null; then GRADLE="gradle"
    else warn "Gradle not found. Falls back to wrapper."; return; fi
    ok "Gradle: ${GRADLE:-not found}"
}
find_gradle

find_sbt() {
    if [[ -n "${SBT_HOME:-}" ]]; then SBT="$SBT_HOME/bin/sbt"
    elif command -v sbt &>/dev/null; then SBT="sbt"
    else warn "SBT not found."; return; fi
    ok "SBT: ${SBT:-not found}"
}
find_sbt

if [[ ! -f "$JAR_PATH" ]]; then
    JAR_PATH=$(find "$PROJECT_DIR" -name "mcp-server-jvm-build-tools.jar" -type f 2>/dev/null | head -1)
fi
if [[ ! -f "$JAR_PATH" ]]; then
    err "JAR not found. Build first: mvn clean package -DskipTests"
    info "Expected: $PROJECT_DIR/target/mcp-server-jvm-build-tools.jar"
    exit 1
fi
ok "JAR: $JAR_PATH"

JVM_OPTS="${MCP_OPTS:-}"
if $HTTP_MODE; then
    info "Transport: Streamable HTTP (port $SERVER_PORT)"
    JVM_OPTS="$JVM_OPTS -Dspring.profiles.active=http -Dserver.port=$SERVER_PORT"
else
    info "Transport: stdio (default)"
fi

echo ""
if $HTTP_MODE; then
    echo -e "  ${GREEN}->${NC}  HTTP:  http://localhost:$SERVER_PORT"
    echo -e "  ${GREEN}->${NC}  Card:  http://localhost:$SERVER_PORT/.well-known/mcp-server"
    echo -e "  ${GREEN}->${NC}  Health: http://localhost:$SERVER_PORT/health"
    echo ""
fi

exec "$JAVA" $JVM_OPTS -jar "$JAR_PATH"
