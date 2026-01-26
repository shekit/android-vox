# Phase 7 Implementation Notes

## Key Architectural Issue Discovered

The accessibility service uses `rootInActiveWindow` to capture UI trees. This returns the UI tree of **whichever app is currently in the foreground**.

**Problem**:
- When android-vox is in foreground, it captures android-vox's own UI tree
- When Settings is in foreground, it captures Settings' UI tree
- During Phase 6 testing: user opened Settings, switched back to android-vox to give command, so Claude saw android-vox's tree instead of Settings

**Implication for Phase 7**:
For the autonomous command loop to work, the **target app must remain in foreground** while the loop executes. The app needs to:
1. Launch target app (which brings it to foreground)
2. Capture its UI tree
3. Send to Claude
4. Execute Claude's action on that app
5. Capture new UI tree
6. Repeat

Android-vox can run in background during this loop. Status updates can be shown via notifications or brief foreground switches.

## Reference
See code comment in [VoxAccessibilityService.kt:41-44](app/src/main/java/com/vox/android/VoxAccessibilityService.kt#L41-L44) explaining rootInActiveWindow behavior.
