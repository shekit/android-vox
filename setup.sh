#!/bin/bash

# android-vox setup script
# Run this after enabling USB debugging on your phone and plugging it in.
# It handles everything else: build, install, accessibility, port forwarding, MCP config.
#
# Usage: ./setup.sh [--port PORT]
#   --port PORT   Local port for connecting to the phone (default: 8080)

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PACKAGE_NAME="com.vox.android"
ACCESSIBILITY_SERVICE="$PACKAGE_NAME/$PACKAGE_NAME.VoxAccessibilityService"
PHONE_PORT=8080
LOCAL_PORT=8080

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --port)
            LOCAL_PORT="$2"
            shift 2
            ;;
        --help|-h)
            echo "Usage: ./setup.sh [--port PORT]"
            echo "  --port PORT   Local port for connecting to the phone (default: 8080)"
            exit 0
            ;;
        *)
            echo "Unknown option: $1 (use --help for usage)"
            exit 1
            ;;
    esac
done

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m'

step=0
step() {
    step=$((step + 1))
    echo ""
    echo -e "${BOLD}[$step] $1${NC}"
}

ok()   { echo -e "    ${GREEN}✓${NC} $1"; }
warn() { echo -e "    ${YELLOW}!${NC} $1"; }
fail() { echo -e "    ${RED}✗${NC} $1"; exit 1; }

echo ""
echo -e "${BOLD}android-vox setup${NC}"
echo "================================"

# ------------------------------------------------------------------
step "Checking environment"
# ------------------------------------------------------------------

# Java
if ! command -v java &> /dev/null; then
    fail "Java not found. Install JDK 17+."
fi

# Set JAVA_HOME for Gradle
if [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    export PATH="$JAVA_HOME/bin:$PATH"
fi

JAVA_MAJOR=$(java -version 2>&1 | head -n 1 | sed -E 's/.*"([0-9]+).*/\1/')
if [ "$JAVA_MAJOR" -lt 17 ] 2>/dev/null; then
    fail "Java $JAVA_MAJOR found, but 17+ is required."
fi
ok "Java $JAVA_MAJOR"

# adb
if ! command -v adb &> /dev/null; then
    # Try default SDK location
    if [ -d "$HOME/Library/Android/sdk/platform-tools" ]; then
        export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
    else
        fail "adb not found. Install Android SDK platform-tools."
    fi
fi
ok "adb"

# ANDROID_HOME
if [ -z "$ANDROID_HOME" ] && [ -d "$HOME/Library/Android/sdk" ]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
fi

# Python
if ! command -v python3 &> /dev/null && ! command -v python &> /dev/null; then
    fail "Python 3 not found. Install Python 3.10+."
fi
PYTHON=$(command -v python3 || command -v python)
ok "Python ($PYTHON)"

# Device
DEVICE_COUNT=$(adb devices | grep -c "device$" || true)
if [ "$DEVICE_COUNT" -eq 0 ]; then
    UNAUTHORIZED=$(adb devices | grep -c "unauthorized" || true)
    if [ "$UNAUTHORIZED" -gt 0 ]; then
        fail "Device connected but unauthorized. Accept the USB debugging prompt on your phone."
    else
        fail "No device connected. Plug in your phone with USB debugging enabled."
    fi
fi
DEVICE_NAME=$(adb devices | grep "device$" | head -n 1 | cut -f1)
ok "Device: $DEVICE_NAME"

# Check if local port is available
if lsof -i ":$LOCAL_PORT" &> /dev/null; then
    fail "Port $LOCAL_PORT is already in use. Run with --port PORT to use a different port."
fi
ok "Port $LOCAL_PORT is available"

# ------------------------------------------------------------------
step "Building and installing app"
# ------------------------------------------------------------------

"$SCRIPT_DIR/scripts/deploy.sh" --launch
ok "App installed and launched"

# ------------------------------------------------------------------
step "Setting up port forwarding"
# ------------------------------------------------------------------

adb forward tcp:$LOCAL_PORT tcp:$PHONE_PORT
ok "localhost:$LOCAL_PORT → phone:$PHONE_PORT"

# ------------------------------------------------------------------
step "Installing Python dependencies"
# ------------------------------------------------------------------

MCP_DIR="$SCRIPT_DIR/sdk/mcp"
VENV_DIR="$MCP_DIR/venv"

if [ ! -d "$VENV_DIR" ]; then
    "$PYTHON" -m venv "$VENV_DIR"
    ok "Created virtual environment"
else
    ok "Virtual environment exists"
fi

"$VENV_DIR/bin/pip" install -q -r "$MCP_DIR/requirements.txt"
ok "Dependencies installed"

# ------------------------------------------------------------------
step "Generating MCP config"
# ------------------------------------------------------------------

MCP_CONFIG="$SCRIPT_DIR/.mcp.json"
PYTHON_PATH="$VENV_DIR/bin/python"
SERVER_PATH="$MCP_DIR/vox_mcp_server.py"

if [ -f "$MCP_CONFIG" ]; then
    warn ".mcp.json already exists — skipping (delete it and re-run to regenerate)"
else
    cat > "$MCP_CONFIG" << EOF
{
  "mcpServers": {
    "vox-phone": {
      "command": "$PYTHON_PATH",
      "args": ["$SERVER_PATH"],
      "env": {
        "VOX_PHONE_HOST": "localhost",
        "VOX_PHONE_PORT": "$LOCAL_PORT"
      }
    }
  }
}
EOF
    ok "Created .mcp.json"
fi

# ------------------------------------------------------------------
echo ""
echo "================================"
echo -e "${GREEN}Setup complete!${NC}"
echo ""
echo "Next:"
echo "  1. On your phone: Settings > Accessibility > android-vox > On"
echo "  2. In the app, tap \"Enable Remote Control\""
echo "  3. Open Claude Code and try: \"Connect to my phone and open the camera\""
echo ""
echo "If Claude Code was already running, you may need to restart it"
echo "for the MCP tools to appear."
echo "================================"
