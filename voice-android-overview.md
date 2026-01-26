# Voice-First Android Interface: Overview

## Concept

An Android experience with zero pre-built UI. The screen is blank except for a live transcription of the user's speech. Claude handles everything: navigating apps via the accessibility tree, and dynamically generating any UI needed (photo grids, confirmation dialogs, maps, settings) in real time.

Goal: push to the extreme — no static UI, no fallback screens, no escape hatches designed in advance. If UI is needed, Claude builds and presents it on the fly.

## Why Android

Android allows third-party apps to:
- Replace the home screen (custom launcher)
- Observe and control any app via Accessibility Services
- Run persistent background services
- Render dynamic UI programmatically

iOS prohibits all of these. This concept is Android-only.

## Core Architecture

### 1. Custom Launcher (Shell)
Blank screen + live transcription display. No buttons, no icons, no navigation. Always listening or push-to-talk (TBD). This is just a rendering surface for whatever Claude decides to show.

### 2. App Control Layer
Needs to read the current state of any app and execute actions (tap, type, scroll, etc.). 

One option: Android's **Accessibility Service** API, which exposes the UI hierarchy (AccessibilityNodeInfo tree) of any foreground app as structured data — not screen scraping.

### 3. Dynamic UI Renderer
A component that takes Claude's UI specifications and renders them natively. Claude might return:
- "Show a grid of these 6 photos, let user tap to select"
- "Show a map centered at X with a route to Y"
- "Show a confirmation dialog: 'Send message to Mom?'"

The renderer interprets these and displays real Android views. After interaction, results go back to Claude.

### 4. Voice + Claude Orchestrator
Coordinates the loop:
- Captures speech → transcribes (Whisper, Deepgram, etc.)
- Gets current UI tree from Accessibility Service (if in another app)
- Sends transcript + context to Claude
- Parses response into: actions, speech, and/or UI to render
- Routes accordingly

## Core Loop

```
User speaks (or screen shows transcription in real time)
→ Transcribe audio
→ Fetch current context (UI tree if in an app, or current state)
→ Send to Claude: "User said X. Context: Y."
→ Claude returns: { actions: [...], say: "...", render: {...} }
→ Execute actions / speak response / render UI
→ Wait for next input or UI interaction
```

## Distribution Model

Normal Play Store app — no custom ROM required.

Onboarding:
1. User installs app
2. Claude (via voice + generated UI) guides user through granting Accessibility permission and setting as default launcher
3. Done

Even onboarding has no pre-built UI — Claude generates the permission prompts and instructions dynamically.

## App Installation

Claude navigates the Play Store like any other app via the accessibility tree.

## Limitations

- Apps with poor accessibility labels degrade experience
- Games/custom canvas rendering have no accessibility tree
- Authentication flows (CAPTCHA, biometrics) require direct interaction
- Complex visual tasks (photo editing, drawing) may hit limits

## Interaction Modes

**Push-to-talk**: User holds to speak, releases to send. Not always listening.

**Direct app access**: User can ask to see any app directly ("open Spotify"). Claude surfaces a button that launches the app with its full native UI. User can return to the voice interface anytime.

**Proactive actions** (future scope): Claude could surface notifications, reminders, or suggestions unprompted. Out of scope for initial build but architecture should not preclude it.

## Minimal MVP

Goal: Test the core question — can Claude reliably understand and navigate real app UIs via the accessibility tree?

Skip for MVP: launcher, voice input, TTS, dynamic UI rendering. Just a test harness.

**What to build:**

1. A simple Android app with a text input and a button
2. An accessibility service that can read the UI tree of any foreground app and execute actions (tap, type, scroll, back)
3. Connection to Claude (direct API or via VPS)

**Test flow:**

1. Open any app manually (e.g., Messages)
2. In the test app, type a command: "send a message to Mom saying I'll be late"
3. App captures the current UI tree, sends to Claude with the command
4. Claude returns an action (e.g., "tap Compose button")
5. App executes it, captures new UI tree, sends back
6. Loop until Claude says done

You'll watch the phone as Claude controls it — apps opening, buttons tapped, text typed. Immediate visual feedback on whether it works.

**This validates:**
- Can the accessibility service read UI trees reliably?
- Can Claude understand and navigate real app UIs?
- Is the back-and-forth loop fast enough?
- How does it handle unexpected states?

If this works, everything else (voice, launcher, dynamic UI) is layered on top.

## Connectivity & Backend

Client-server architecture. The Android app is a thin client; Claude runs in the cloud.

The app gathers input (voice, UI tree), sends to the backend, receives commands, executes them locally. Claude never touches the phone directly — it just receives state and returns instructions.

**Backend options:**
- **Direct API**: App calls Anthropic's API directly. Simpler, no infra needed.
- **Stateful VPS**: App calls your server, which maintains persistent storage (memory, context, files), manages Claude API calls, and can run Claude Code for complex tasks. Enables continuity across sessions, multi-device support, and capabilities beyond what the phone can do locally.

**Requires internet.** No connectivity = no Claude. Minimal offline fallback possible but core functionality needs a connection.

## Persistence

As the default launcher, the app cannot be "closed" like a normal app. Home button always returns to it. The only exit is manually changing the default launcher in Settings.

For more extreme lockdown (dedicated devices), Android supports Kiosk Mode (Lock Task Mode) which removes navigation buttons, status bar, and app switching entirely. Requires either user-enabled screen pinning or Device Owner provisioning. Overkill for consumer distribution but relevant for dedicated hardware.

## Open Questions

- How sophisticated does the dynamic UI renderer need to be? (Simple views vs. full mini-app framework)
- How to handle latency for complex UI generation?
- Offline fallback behavior?
