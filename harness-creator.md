# Harness Creator Guide

This document describes how to set up a **development harness** for any project that Claude will work on iteratively. The harness provides structure, tracking, and protocols that ensure reliable progress across multiple sessions.

## Overview

A harness consists of:
- **CLAUDE.md** — Project-specific instructions for Claude
- **claude-progress.md** — Session history and current state
- **feature-list** — Structured feature tracking (JSON, YAML, or other format)
- **init script** — Environment verification
- **scripts/** — Build/deploy/test scripts with environment setup baked in

## Core Concepts

### Work Hierarchy

Organize work into a hierarchy:

**Phases** → **Features**

- **Phases** are major milestones with a **testable outcome** — something you can verify at the end (e.g., "app launches on device", "user can log in")
- **Features** are atomic units of work within a phase, small enough to complete and commit individually
- Each feature has a **test** — how you verify it works
- Each feature tracks **pass/fail status**

The feature list is the **source of truth** for what's done and what's pending. Don't duplicate this information elsewhere.

### Atomic Commits

One feature = one commit. After completing each feature:
1. Verify it works
2. Mark it as passing in the feature list
3. Commit immediately
4. Move to the next feature

Never batch commits at the end of a phase.

### Environment Independence

Never rely on the user's shell configuration (`~/.zshrc`, `~/.bashrc`). Instead:
- Set environment variables in scripts
- Use project config files where the platform supports it
- Have init scripts verify and set up the environment

### Documentation Lookup

Use the Context7 MCP to look up current library documentation before implementing code with external libraries. Don't rely on training data for API details — models, API shapes, and parameters change frequently.

## Setup Steps

### 1. Create Feature List

Create a file that tracks all work organized by phases and features. Use whatever format suits your project (JSON, YAML, etc.).

Each feature should include:
- Unique identifier (e.g., P1.1, P1.2)
- Description of what it does
- How to test/verify it
- Pass/fail status

**Rules:**
- Each feature must have a concrete, verifiable test
- Status starts as "not passing", updated when verified
- This file is the single source of truth for progress

### 2. Create Progress Log (`claude-progress.md`)

A simple log of session history:
- Current status (which phase, what state)
- Session entries with what was accomplished
- Any blockers or issues

**Rules:**
- Do NOT add "next steps" — use the feature list for that
- Keep entries concise
- Focus on what was done, not what will be done

### 3. Create Project Instructions (`CLAUDE.md`)

Include these sections:

#### Commit Protocol

```markdown
### CRITICAL: Commit After EVERY Feature

**This is a hard requirement. Do not batch commits.**

After completing each feature:
1. Verify it works
2. Update feature list to mark as passing
3. **Immediately commit**
4. Then move to the next feature

**Only commit files YOU created or modified:**
- Use `git add <specific-files>` — NOT `git add .` or `git add -A`
- Check `git status` first
- Only stage files you directly worked on
```

#### Session Protocol

```markdown
### Starting a Session
1. Read progress log to understand current state
2. Read feature list to see what's done/pending
3. Check recent git history
4. Run init script to verify environment

### During a Session
- Work on ONE feature at a time
- Update feature list when a feature passes/fails

### Ending a Session
- Commit all work in progress
- Update progress log with what was accomplished and current state
- Do NOT add "next steps" — the feature list handles that
```

#### Development Feedback Loop

```markdown
### Development Feedback Loop

After writing or changing code:

1. **Build**: Run the build
2. **Run/Deploy**: Execute or deploy
3. **Check Output**: Look at logs, errors, behavior
4. **Understand**: Did it work? Why or why not?
5. **Fix & Repeat**: If broken, fix and go back to step 1
6. **Clean Up**: Stop running processes after testing

Never assume code works because it compiled — always verify.
```

#### Logging

```markdown
### Logging in Code

Add logging at key points so there's something to check:
- Important events
- State changes
- Errors with context

Use consistent tags/prefixes so logs are easy to filter.
```

#### Manual Testing

```markdown
### Requesting Manual Testing

If you need the user to test something, present it as a brief table:

| Action | Expected Outcome |
|--------|------------------|
| Do X | Y happens |
| Do A | B appears |

Keep it succinct. Don't leave things running without explicitly requesting testing.
```

### 4. Create Init Script

The init script should:
1. Set environment variables needed for the project
2. Verify all dependencies are present
3. Check connections (devices, servers, databases, etc.)

**Key principle:** Don't assume the user's shell is configured correctly. Set what you need.

### 5. Create Build/Deploy Scripts

**Key principle:** Bake environment setup INTO the scripts so they work regardless of shell configuration.

Each script should:
1. Set any required environment variables at the top
2. Do its job (build, deploy, test, etc.)
3. Provide clear output on success or failure

Also configure environment in **project config files** where the platform supports it (e.g., specifying runtime versions, SDK paths).

### 6. Handle Secrets

For API keys, credentials, and other secrets:
- Create a template file showing what's needed (e.g., `.env.example`, `config.example.json`)
- Gitignore the actual secrets file
- Document in CLAUDE.md how to set up secrets

The exact approach varies by platform — use whatever is idiomatic for your stack.

### 7. Configure `.gitignore`

Ignore:
- Build artifacts
- Local config with secrets
- IDE folders
- OS files

Include:
- Any wrapper/bootstrap files needed to build from scratch

## Common Pitfalls

### Command Output Buffering

Avoid piping command output through `head`, `tail`, `less`, or `more` — these can cause buffering issues or hangs. Use command-native flags instead:
- `git log -n 10` instead of `git log | head -10`
- Platform-specific equivalents for other tools

### Cached State

When environment changes (new SDK version, different runtime), cached state can cause confusing errors. Know how to clear caches for your platform:
- Stop background daemons/servers
- Clear build caches
- Remove generated files

### Device/Service Connectivity

For projects that connect to external devices or services, the init script should verify connectivity is working before starting work.

## Checklist

Before handing off to Claude:

- [ ] Feature list created with phases and features
- [ ] Progress log initialized
- [ ] CLAUDE.md has project-specific instructions
- [ ] Init script verifies environment
- [ ] Build/deploy scripts created with environment setup
- [ ] Secrets handling documented with template files
- [ ] `.gitignore` configured
- [ ] Git repo initialized
- [ ] At least one working build/deploy cycle verified manually
