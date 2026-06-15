#!/bin/bash
# Local development launcher for MTG Gameplay
# Starts the XMage server and the Vite dev server in parallel.
# Press Ctrl+C once to stop both.

set -e

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.10/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"

echo "[start-dev] Building XMage server (skipping tests)..."
mvn install -pl Mage.Server -am -DskipTests -q -f "$REPO_ROOT/pom.xml"

echo "[start-dev] Resolving classpath..."
mvn -q dependency:build-classpath \
  -pl Mage.Server \
  -f "$REPO_ROOT/pom.xml" \
  -DincludeScope=runtime \
  -Dmdep.outputFile=/tmp/xmage-classpath.txt

CP=$(cat /tmp/xmage-classpath.txt)
CP="$CP:$REPO_ROOT/Mage.Server/target/mage-server.jar"

echo "[start-dev] Starting XMage server on ports 17171 (game) + 17172 (WebSocket API)..."
(cd "$REPO_ROOT/Mage.Server" && java -cp "$CP" mage.server.Main) &
SERVER_PID=$!

echo "[start-dev] Starting React dev server (Vite)..."
cd "$REPO_ROOT/frontend" && npm run dev &
FRONTEND_PID=$!

# Trap Ctrl+C and kill both processes cleanly
cleanup() {
  echo ""
  echo "[start-dev] Stopping servers..."
  kill "$SERVER_PID" 2>/dev/null || true
  kill "$FRONTEND_PID" 2>/dev/null || true
  exit 0
}
trap cleanup INT TERM

wait
