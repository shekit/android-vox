# Quick Start

## Run with Claude Code (MCP)

1. Plug in phone via USB
2. `adb forward tcp:8080 tcp:8080`
3. On phone: Open Vox → tap **Start Server**
4. Ask Claude Code to control your phone

## Run Locally (on-phone AI)

1. On phone: Open Vox → enter API key → type command → tap **Ask Claude**

## How They Work

**Local Mode**: Phone builds a prompt with the UI tree + available actions + your command, sends it to Claude API, gets back an action, executes it, repeats until done. The phone orchestrates the loop.

**MCP Mode**: Claude Code calls phone tools directly (`phone_get_screenshot`, `phone_execute`, etc.). Claude decides when to look at the screen, what to try, and when it's done. Claude orchestrates the loop.

## Key Differences

| | Local Mode | MCP Mode |
|--|------------|----------|
| AI runs on | Phone (via API) | Claude Code |
| Who orchestrates | Phone app | Claude Code |
| How Claude knows actions | Phone sends in prompt | MCP tool descriptions |
| UI tree delivery | Included in prompt | Claude calls `phone_get_screenshot` |
| Setup | Just the app | USB + port forwarding |
