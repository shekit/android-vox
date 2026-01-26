# Claude Instructions for android-vox

> **⚠️ IMPORTANT: Commit after EVERY feature, not at the end of phases. See "CRITICAL: Commit After EVERY Feature" section below.**

## Project Overview

Voice-first Android interface where Claude controls apps via the Accessibility Service API. The screen is blank except for live transcription — Claude handles navigation and dynamically generates UI as needed.

**Current phase**: MVP — validating that Claude can reliably understand and navigate real app UIs via the accessibility tree.

## MVP Scope

Building a minimal test harness:
1. Simple Android app with text input + button
2. Accessibility service that reads UI trees and executes actions (tap, type, scroll, back)
3. Connection to Claude API

**Not in MVP**: Voice input, TTS, custom launcher, dynamic UI rendering.

## Session Protocol

### Starting a Session
1. Read `claude-progress.md` to understand current state
2. Read `feature-list.json` to see what's done/pending
3. Check `git log --oneline -10` for recent changes
4. Run `./init.sh` to verify environment is ready

### During a Session
- Work on ONE feature at a time
- Update `feature-list.json` when a feature passes/fails

### CRITICAL: Commit After EVERY Feature

**This is a hard requirement. Do not batch commits.**

After completing each individual feature (e.g., P1.1, P1.2, P1.3):
1. Verify the feature works
2. Update `feature-list.json` to mark it as `"passes": true`
3. **Immediately commit** with a message like: `P1.1: Gradle project initializes`
4. Then move to the next feature

❌ **WRONG**: Complete P1.1, P1.2, P1.3, P1.4, then commit once at end of phase
✅ **RIGHT**: Complete P1.1 → commit → Complete P1.2 → commit → Complete P1.3 → commit → etc.

**Only commit files YOU created or modified:**
- Use `git add <specific-files>` — NOT `git add .` or `git add -A`
- Do not commit files modified by the user or other sessions
- Check `git status` first to see what's changed
- Only stage files you directly worked on for the current feature

This ensures:
- Progress is never lost
- Each feature is a recoverable checkpoint
- Easy to revert if something breaks
- User changes are not accidentally included

### Development Feedback Loop

After writing or changing code, always follow this loop:

```
Build → Deploy → Check Logs → Understand → Fix → Repeat
```

1. **Build**: `./gradlew assembleDebug`
   - If build fails, read the error output, fix, rebuild

2. **Deploy & Launch**: `./gradlew installDebug && adb shell am start -n com.vox.android/.MainActivity`
   - Or use `./scripts/deploy.sh --launch`

3. **Check Logs**: `adb logcat -d | grep -i "vox\|exception\|error\|crash"`
   - Look for crashes, exceptions, and app-specific logs
   - The app should include logging at key points (events, state changes, errors)
   - Use `adb logcat` (streaming) to watch behavior in real-time if needed

4. **Understand**: Read the logs to see what actually happened
   - Did the code path execute?
   - Did it crash? Where?
   - Is the state what you expected?

5. **Fix & Repeat**: If something's wrong, fix it and go back to step 1

6. **Clean Up**: After verifying the feature works, quit the app:
   ```bash
   adb shell am force-stop com.vox.android
   ```
   - Do NOT leave the app running after your tests
   - Only leave it running if you want the user to manually test something
   - If you want user testing, explicitly say so: "Please test X on the device"

This loop is mandatory. Never assume code works just because it compiled — always verify via logs.

### Logging in Code

Add logging at key points so there's something useful to check:
- Important events (button clicks, accessibility events received)
- State changes (service enabled, API response received)
- Errors and exceptions (with context about what was happening)

Use a consistent tag so logs are easy to filter.

### Ending a Session
- Commit all work in progress
- Update `claude-progress.md` with:
  - What was accomplished
  - Current state
  - Any blockers or issues
  - Suggested next steps

## Project Structure

```
android-vox/
├── app/                        # Android application module
│   ├── src/main/
│   │   ├── java/              # Kotlin/Java source files
│   │   ├── res/               # Resources (layouts, strings, etc.)
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── scripts/
│   └── deploy.sh              # Build/install/launch script
├── init.sh                    # Environment bootstrap
├── claude-progress.md         # Work history
├── feature-list.json          # Feature tracking
└── build.gradle.kts           # Root build file
```

## Key Commands

```bash
# Environment
./init.sh                      # Check environment, device connection
adb devices                    # Check connected devices

# Build & Deploy
./scripts/deploy.sh --build    # Build only
./scripts/deploy.sh --install  # Build + install
./scripts/deploy.sh --launch   # Build + install + launch (default)
./scripts/deploy.sh --clean    # Clean build

# Logs & Debugging
adb logcat -d                  # Dump recent logs
adb logcat                     # Stream logs (Ctrl+C to stop)
adb logcat -c                  # Clear log buffer
adb logcat -s "TAG:*"          # Filter by tag
adb shell dumpsys activity activities | grep -A5 "mResumed"  # Current activity
```

## Command Execution Guidelines

**Avoid commands that cause output buffering issues:**
- DO NOT pipe output through `head`, `tail`, `less`, or `more` — these cause buffering problems
- Use command-native flags instead:
  - `git log -n 10` instead of `git log | head -10`
  - `adb logcat -d -t 50` instead of `adb logcat | tail -50`
- Let commands complete fully rather than truncating with pipes
- Never use interactive commands (`less`, `more`, `vim`) — they'll hang

## Accessibility Service Notes

- Service must be declared in AndroidManifest.xml
- Requires user to manually enable in Settings → Accessibility
- Use `AccessibilityNodeInfo` to traverse UI tree
- Actions: `performAction(ACTION_CLICK)`, `performAction(ACTION_SET_TEXT)`, etc.
- The service runs in a separate process from the main app

## Testing Approach

1. Open any app manually (Messages, Settings, etc.)
2. Type command in test app: "tap the compose button"
3. App captures UI tree → sends to Claude
4. Claude returns action → app executes
5. Loop until task complete

## Conventions

- Language: Kotlin
- Min SDK: 26 (Android 8.0)
- Target SDK: 34 (Android 14)
- Package name: `com.vox.android`
- Use Material 3 components where UI is needed

## API Key Setup

The Claude API key is stored in `local.properties` (gitignored) and injected at build time.

**Setup:**
1. Copy `local.properties.example` to `local.properties`
2. Add your API key: `CLAUDE_API_KEY=sk-ant-xxxxx`

**In build.gradle.kts** (when Phase 6 is implemented):
```kotlin
android {
    defaultConfig {
        buildConfigField("String", "CLAUDE_API_KEY", "\"${properties["CLAUDE_API_KEY"]}\"")
    }
}
```

**In code:**
```kotlin
val apiKey = BuildConfig.CLAUDE_API_KEY
```
