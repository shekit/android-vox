# Phase 7 Implementation Notes

## Context
Phase 6 is complete - Claude API integration works, but currently only displays Claude's response without executing it.

## Key Architectural Issue Identified
The accessibility service captures the UI tree of the **active window** (rootInActiveWindow). When android-vox is in foreground, it captures android-vox's own UI tree instead of the target app.

## Phase 7 Solution
Create an autonomous command loop that:

1. **Keeps target app in foreground**: After launching the target app, keep it in foreground so the accessibility service captures its UI tree
2. **Android-vox runs in background**: Use notifications or background service to show status
3. **Execute Claude's actions automatically**: Parse and execute the action commands Claude returns
4. **Loop until complete**: Continue asking Claude and executing until Claude says "done"

## Implementation Steps

### P7.1: Single command → action cycle
- Replace "Ask Claude" button with "Run Task" button
- When user enters command: "Turn on Wi-Fi"
  1. Send initial command + empty/current UI tree to Claude
  2. Claude responds with first action: "launch Settings"
  3. Parse and execute that action
  4. Wait for UI to stabilize (delay ~1-2 seconds)
  5. Display status update

### P7.2: Loop continues until done
- After executing an action, capture new UI tree
- Send context to Claude: "User wants to: [original task]. Last action: [what we just did]"
- Claude responds with next action or "done"
- Parse response:
  - If "done" → show completion message, stop loop
  - If action → execute it, continue loop
- Add max iteration limit (e.g., 20) to prevent infinite loops

### P7.3: Status updates during loop
- Show current step in UI: "Step 1: Launching Settings..."
- Update after each action: "Step 2: Tapping Wi-Fi..."
- Keep a running log visible to user
- Option: Minimize android-vox after task starts, show notification with progress

### P7.4: Error handling
- If action fails (e.g., can't find node), report to Claude and let it retry
- If action fails 3 times in a row, stop and show error
- Catch parse errors and show clear messages
- Handle API errors gracefully

### P7.5: Full multi-step task
- Test with complex task: "Send a text to Mom saying hello"
  - Launch Messages app
  - Tap compose button
  - Type "Mom" in recipient
  - Tap contact
  - Type "hello" in message
  - Tap send
  - Verify sent

## Code Changes Needed

### 1. Create CommandLoopExecutor class
```kotlin
class CommandLoopExecutor(
    private val apiKey: String,
    private val service: VoxAccessibilityService,
    private val statusCallback: (String) -> Unit
) {
    private var isRunning = false
    private var stepCount = 0
    private val MAX_STEPS = 20

    fun startTask(userCommand: String) { ... }
    private fun executeLoop(userCommand: String, lastAction: String?) { ... }
    private fun executeAction(action: String): Boolean { ... }
    private fun parseAndExecute(actionCommand: String): Boolean { ... }
}
```

### 2. Update MainActivity
- Add "Run Task" button (or repurpose "Ask Claude")
- Create CommandLoopExecutor instance
- Pass status callback to update UI
- Handle loop completion

### 3. Update ClaudeApiClient prompt
- Include original user task in every request
- Include what action was just performed
- Make it clear Claude should respond with next action or "done"

### 4. Action Executor
- Reuse existing action parsing from P5.7 (the "Send" button)
- Extract into shared function: `executeActionCommand(command: String): Boolean`
- Return true/false for success/failure

## Testing Strategy
Start simple and build up:
1. "Launch Settings" - single action
2. "Open Wi-Fi settings" - 2 actions (launch + tap)
3. "Turn on Airplane mode" - 3+ actions (launch + scroll + tap toggle)
4. Complex: "Send a text to John saying hello"

## UI/UX Considerations
- User should be able to see what's happening
- Option to cancel mid-loop
- Clear error messages if something fails
- Success confirmation when task completes
- For MVP: android-vox can stay in foreground showing logs
- Future: minimize and use notifications

## Common Pitfalls to Avoid
1. **Don't capture UI too quickly**: Wait 1-2 seconds after actions for UI to update
2. **Don't run infinite loops**: Set max iteration count
3. **Don't ignore Claude's "done"**: Make sure to detect completion
4. **Don't fail silently**: Report errors clearly to user
5. **Don't block UI thread**: Use coroutines or Handler for delays/loops
