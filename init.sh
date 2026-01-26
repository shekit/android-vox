#!/bin/bash

# android-vox environment initialization script
# Run this at the start of each session to verify everything is ready

set -e

# Set JAVA_HOME to Android Studio's bundled JDK (requires JDK 17+)
if [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    export PATH="$JAVA_HOME/bin:$PATH"
fi

# Set ANDROID_HOME if not set but SDK exists in default location
if [ -z "$ANDROID_HOME" ] && [ -d "$HOME/Library/Android/sdk" ]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
    export PATH="$ANDROID_HOME/platform-tools:$PATH"
fi

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "================================"
echo "android-vox Environment Check"
echo "================================"
echo ""

ERRORS=0

# Check Java (requires JDK 17+)
echo -n "Checking Java... "
if command -v java &> /dev/null; then
    JAVA_VERSION=$(java -version 2>&1 | head -n 1)
    # Extract major version number
    JAVA_MAJOR=$(java -version 2>&1 | head -n 1 | sed -E 's/.*"([0-9]+).*/\1/')
    if [ "$JAVA_MAJOR" -ge 17 ] 2>/dev/null; then
        echo -e "${GREEN}OK${NC} - $JAVA_VERSION"
    else
        echo -e "${RED}TOO OLD${NC} - $JAVA_VERSION"
        echo "  Requires JDK 17+. Install Android Studio or set JAVA_HOME."
        ERRORS=$((ERRORS + 1))
    fi
else
    echo -e "${RED}MISSING${NC}"
    echo "  Install JDK 17+: https://adoptium.net/"
    ERRORS=$((ERRORS + 1))
fi

# Check ANDROID_HOME
echo -n "Checking ANDROID_HOME... "
if [ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME" ]; then
    echo -e "${GREEN}OK${NC} - $ANDROID_HOME"
else
    echo -e "${RED}NOT SET${NC}"
    echo "  Set ANDROID_HOME to your Android SDK path"
    echo "  Usually: ~/Library/Android/sdk (Mac) or ~/Android/Sdk (Linux)"
    ERRORS=$((ERRORS + 1))
fi

# Check ADB
echo -n "Checking ADB... "
if command -v adb &> /dev/null; then
    ADB_VERSION=$(adb version | head -n 1)
    echo -e "${GREEN}OK${NC} - $ADB_VERSION"
else
    echo -e "${RED}MISSING${NC}"
    echo "  ADB should be at \$ANDROID_HOME/platform-tools/adb"
    ERRORS=$((ERRORS + 1))
fi

# Check connected device
echo -n "Checking connected device... "
if command -v adb &> /dev/null; then
    DEVICE_COUNT=$(adb devices | grep -c "device$" || true)
    if [ "$DEVICE_COUNT" -gt 0 ]; then
        DEVICE_NAME=$(adb devices | grep "device$" | head -n 1 | cut -f1)
        echo -e "${GREEN}OK${NC} - $DEVICE_NAME"
    else
        # Check if unauthorized
        UNAUTHORIZED=$(adb devices | grep -c "unauthorized" || true)
        if [ "$UNAUTHORIZED" -gt 0 ]; then
            echo -e "${YELLOW}UNAUTHORIZED${NC}"
            echo "  Check your phone for 'Allow USB debugging' prompt"
            ERRORS=$((ERRORS + 1))
        else
            echo -e "${YELLOW}NO DEVICE${NC}"
            echo "  Connect Android device via USB with USB Debugging enabled"
            echo "  Or start an emulator: emulator -avd <avd_name>"
        fi
    fi
else
    echo -e "${RED}SKIPPED${NC} (ADB not available)"
fi

# Check Gradle wrapper
echo -n "Checking Gradle wrapper... "
if [ -f "./gradlew" ]; then
    echo -e "${GREEN}OK${NC}"
else
    echo -e "${YELLOW}NOT FOUND${NC}"
    echo "  Android project not yet initialized"
fi

echo ""
echo "================================"

if [ $ERRORS -gt 0 ]; then
    echo -e "${RED}Environment check failed with $ERRORS error(s)${NC}"
    echo "Fix the issues above before proceeding."
    exit 1
else
    echo -e "${GREEN}Environment ready!${NC}"
    exit 0
fi
