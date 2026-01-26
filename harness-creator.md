# Harness Creator Guide

This document describes how to set up a **development harness** for any project that Claude will work on iteratively. The harness provides structure, tracking, and protocols that ensure reliable progress across multiple sessions.

## Overview

A harness consists of:
- **CLAUDE.md** — Project-specific instructions for Claude
- **claude-progress.md** — Session history and current state
- **feature-list.json** — Structured feature tracking with pass/fail status
- **init.sh** — Environment verification script
- **scripts/** — Build/deploy/test scripts with environment setup baked in

## Setup Steps

### 1. Create Feature List (`feature-list.json`)

Structure work into **phases**, each with **testable features**:

```json
{
  "project": "project-name",
  "version": "mvp",
  "phases": [
    {
      "id": "P1",
      "name": "Phase Name",
      "testable_outcome": "One sentence describing what proves this phase works",
      "features": [
        {
          "id": "P1.1",
          "description": "What this feature does",
          "test": "How to verify it works",
          "passes": false
        }
      ]
    }
  ]
}
```

**Rules:**
- Each feature must have a concrete, verifiable test
- `passes` starts as `false`, set to `true` when verified
- This file is the **source of truth** for what's done/pending

### 2. Create Progress Log (`claude-progress.md`)

```markdown
# Claude Progress Log

## Current Status

**Phase**: [Current phase name]
**State**: [One-line description of current state]

## Session History

### Session N — YYYY-MM-DD
**Focus**: [What this session worked on]

**Completed**:
- [Bullet points of what was done]

---
```

**Rules:**
- Do NOT add "next steps" — use `feature-list.json` for that
- Keep entries concise
- Note any blockers or issues

### 3. Create Project Instructions (`CLAUDE.md`)

Include these sections:

#### Commit Protocol (CRITICAL)

```markdown
### CRITICAL: Commit After EVERY Feature

**This is a hard requirement. Do not batch commits.**

After completing each individual feature (e.g., P1.1, P1.2, P1.3):
1. Verify the feature works
2. Update `feature-list.json` to mark it as `"passes": true`
3. **Immediately commit** with a message like: `P1.1: Feature description`
4. Then move to the next feature

❌ **WRONG**: Complete P1.1, P1.2, P1.3, P1.4, then commit once at end of phase
✅ **RIGHT**: Complete P1.1 → commit → Complete P1.2 → commit → etc.

**Only commit files YOU created or modified:**
- Use `git add <specific-files>` — NOT `git add .` or `git add -A`
- Check `git status` first to see what's changed
- Only stage files you directly worked on for the current feature
```

#### Session Protocol

```markdown
### Starting a Session
1. Read `claude-progress.md` to understand current state
2. Read `feature-list.json` to see what's done/pending
3. Check `git log --oneline -10` for recent changes
4. Run `./init.sh` to verify environment is ready

### During a Session
- Work on ONE feature at a time
- Update `feature-list.json` when a feature passes/fails

### Ending a Session
- Commit all work in progress
- Update `claude-progress.md` with:
  - What was accomplished
  - Current state
  - Any blockers or issues
- Do NOT add "next steps" — use `feature-list.json` to determine what's pending
```

#### Development Feedback Loop

```markdown
### Development Feedback Loop

After writing or changing code, always follow this loop:

1. **Build**: Run the build script
2. **Deploy/Run**: Execute or deploy the code
3. **Check Output**: Look at logs, errors, or behavior
4. **Understand**: Did it work? If not, why?
5. **Fix & Repeat**: If something's wrong, fix and go back to step 1
6. **Clean Up**: Stop any running processes after testing

Never assume code works just because it compiled — always verify.
```

#### Manual Testing Requests

```markdown
### Requesting Manual Testing

If you need the user to test something manually, present it as a **brief table**:

| Action | Expected Outcome |
|--------|------------------|
| Click the button | Dialog appears |
| Enter "test" and submit | "test" shown in output |

Keep it succinct. Don't leave things running without explicitly requesting testing.
```

### 4. Create Environment Script (`init.sh`)

The init script should:
1. **Set environment variables** needed for the project
2. **Verify** all dependencies are present
3. **Check** connections (devices, servers, etc.)

```bash
#!/bin/bash
set -e

# === SET ENVIRONMENT VARIABLES ===
# Don't rely on user's shell config — set them here
if [ -d "/path/to/required/tool" ]; then
    export TOOL_HOME="/path/to/required/tool"
    export PATH="$TOOL_HOME/bin:$PATH"
fi

# === VERIFICATION CHECKS ===
ERRORS=0

echo "Checking dependencies..."

# Check each required tool
if command -v required_tool &> /dev/null; then
    echo "✓ required_tool found"
else
    echo "✗ required_tool missing"
    ERRORS=$((ERRORS + 1))
fi

# Final result
if [ $ERRORS -gt 0 ]; then
    echo "Environment check failed"
    exit 1
else
    echo "Environment ready!"
    exit 0
fi
```

### 5. Create Build/Deploy Scripts

**Key principle:** Bake environment setup INTO the scripts so they work regardless of shell configuration.

```bash
#!/bin/bash
set -e

# === ENVIRONMENT SETUP (redundant but reliable) ===
if [ -d "/path/to/tool" ]; then
    export TOOL_HOME="/path/to/tool"
fi

# === SCRIPT LOGIC ===
echo "Building..."
# build commands here

echo "Done"
```

Also add environment config to **project config files** where possible:
- For Gradle: `org.gradle.java.home=/path/to/jdk` in `gradle.properties`
- For Node: `engines` in `package.json`
- For Python: version in `pyproject.toml`

This ensures builds work even if scripts aren't used.

### 6. Update `.gitignore`

Ensure these are ignored:
- Build artifacts
- Local config with secrets (e.g., `local.properties`, `.env`)
- IDE folders
- OS files (`.DS_Store`, `Thumbs.db`)

But **include**:
- Wrapper files needed to build (e.g., `!gradle/wrapper/gradle-wrapper.jar`)

## Harness Principles

### 1. Environment Independence
Never rely on user's shell config (`~/.zshrc`, `~/.bashrc`). Set environment variables in:
- Project config files (best — works for everyone)
- Scripts (good — works when scripts are used)
- init.sh (verification — catches issues early)

### 2. Atomic Commits
One feature = one commit. This ensures:
- Progress is never lost
- Easy to revert if something breaks
- Clear history of what changed when

### 3. Single Source of Truth
- `feature-list.json` defines what's done and what's next
- Don't duplicate this in progress files or comments

### 4. Verify, Don't Assume
- Always run the code after changes
- Check logs/output for actual behavior
- Clean up after testing

### 5. Scripts Over Raw Commands
- Wrap complex commands in scripts
- Include environment setup in scripts
- Document scripts in CLAUDE.md

## Checklist

Before handing off to Claude:

- [ ] `feature-list.json` created with all phases and features
- [ ] `claude-progress.md` initialized with current status
- [ ] `CLAUDE.md` has project-specific instructions
- [ ] `init.sh` verifies environment and sets variables
- [ ] Build/deploy scripts created with environment setup
- [ ] `.gitignore` configured properly
- [ ] Git repo initialized with clean state
- [ ] At least one working build/deploy cycle verified manually
