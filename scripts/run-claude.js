#!/usr/bin/env node
/**
 * Simple script to run Claude Code CLI as a subprocess.
 * Spawns Claude interactively and pipes the prompt - this allows
 * multi-turn agentic execution just like in terminal/VS Code.
 *
 * Usage:
 *   node scripts/run-claude.js "your prompt here"
 *   node scripts/run-claude.js --continue "your prompt here"
 *   node scripts/run-claude.js -c "your prompt here"
 */

const { spawn } = require('child_process');
const path = require('path');

// Parse arguments
const args = process.argv.slice(2);

let shouldContinue = false;
let prompt = '';

// Check for --continue or -c flag
if (args[0] === '--continue' || args[0] === '-c') {
    shouldContinue = true;
    prompt = args.slice(1).join(' ');
} else {
    prompt = args.join(' ');
}

if (!prompt) {
    console.log('Usage:');
    console.log('  node scripts/run-claude.js "your prompt"');
    console.log('  node scripts/run-claude.js --continue "your prompt"  (resume last conversation)');
    console.log('  node scripts/run-claude.js -c "your prompt"          (shorthand)');
    process.exit(1);
}

// Build claude command args - NO --print, run interactively
const claudeArgs = [
    '--permission-mode', 'bypassPermissions',  // Don't ask for permissions
    '--verbose'
];

if (shouldContinue) {
    claudeArgs.push('--continue');
}

console.log('---');
console.log(`Running Claude ${shouldContinue ? '(continuing previous session)' : '(new session)'}`);
console.log(`Prompt: ${prompt}`);
console.log('---\n');

// Spawn claude in the project directory
const projectDir = path.resolve(__dirname, '..');

const claude = spawn('claude', claudeArgs, {
    cwd: projectDir,
    stdio: ['pipe', 'inherit', 'inherit'],  // pipe stdin, inherit stdout/stderr
    shell: true
});

// Send the prompt and then close stdin to signal we're done
claude.stdin.write(prompt);
claude.stdin.end();

claude.on('error', (err) => {
    console.error('Failed to start Claude:', err.message);
    process.exit(1);
});

claude.on('close', (code) => {
    console.log('\n---');
    console.log(`Claude exited with code ${code}`);

    if (code === 0) {
        console.log('\nTip: Use --continue (-c) next time to resume this conversation');
    }
});
