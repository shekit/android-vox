#!/bin/bash

# android-vox build and deploy script
# Usage: ./scripts/deploy.sh [flags]
#
# Flags:
#   --build, -b     Build only (no install)
#   --install, -i   Build + install to device
#   --launch, -l    Build + install + launch app (default)
#   --clean, -c     Clean build first
#   --release, -r   Build release instead of debug
#   --help, -h      Show this help message

set -e

# Default values
ACTION="launch"
CLEAN=false
BUILD_TYPE="debug"
PACKAGE_NAME="com.vox.android"
MAIN_ACTIVITY="com.vox.android.MainActivity"

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --build|-b)
            ACTION="build"
            shift
            ;;
        --install|-i)
            ACTION="install"
            shift
            ;;
        --launch|-l)
            ACTION="launch"
            shift
            ;;
        --clean|-c)
            CLEAN=true
            shift
            ;;
        --release|-r)
            BUILD_TYPE="release"
            shift
            ;;
        --help|-h)
            head -n 13 "$0" | tail -n 11
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage"
            exit 1
            ;;
    esac
done

# Capitalize build type for Gradle task names
if [ "$BUILD_TYPE" = "debug" ]; then
    BUILD_TYPE_CAP="Debug"
else
    BUILD_TYPE_CAP="Release"
fi

echo "=== android-vox deploy ==="
echo "Action: $ACTION | Build: $BUILD_TYPE | Clean: $CLEAN"
echo ""

# Check if gradlew exists
if [ ! -f "./gradlew" ]; then
    echo "Error: gradlew not found. Android project not initialized."
    echo "Initialize the project first before running deploy."
    exit 1
fi

# Clean if requested
if [ "$CLEAN" = true ]; then
    echo ">>> Cleaning build..."
    ./gradlew clean
    echo ""
fi

# Build
echo ">>> Building $BUILD_TYPE APK..."
./gradlew assemble$BUILD_TYPE_CAP

if [ "$ACTION" = "build" ]; then
    echo ""
    echo "=== Build complete ==="
    exit 0
fi

# Install
echo ""
echo ">>> Installing to device..."

# Check device connected
DEVICE_COUNT=$(adb devices | grep -c "device$" || true)
if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo "Error: No device connected. Connect a device or start an emulator."
    exit 1
fi

./gradlew install$BUILD_TYPE_CAP

if [ "$ACTION" = "install" ]; then
    echo ""
    echo "=== Install complete ==="
    exit 0
fi

# Launch
echo ""
echo ">>> Launching app..."
adb shell am start -n "$PACKAGE_NAME/$MAIN_ACTIVITY"

echo ""
echo "=== Deploy complete ==="
