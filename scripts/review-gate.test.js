'use strict';
// node --test scripts/review-gate.test.js
// The review gate (.claude/hooks/review-gate.sh) asks before a push when no review ran on this tree. These tests run
// the real hook with hook JSON on stdin, the way Claude Code calls it.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const ROOT = path.join(__dirname, '..');
const HOOK = path.join(ROOT, '.claude', 'hooks', 'review-gate.sh');

// A project dir without a review stamp: every detected push must ask.
const PROJECT = fs.mkdtempSync(path.join(os.tmpdir(), 'review-gate-'));

function decision(command) {
  const r = spawnSync('bash', [HOOK], {
    input: JSON.stringify({ tool_input: { command } }),
    env: { ...process.env, CLAUDE_PROJECT_DIR: PROJECT },
    encoding: 'utf8',
    maxBuffer: 16 * 1024 * 1024,
  });
  assert.equal(r.status, 0, `hook exit for ${command.slice(0, 60)}: ${r.stderr}`);
  return r.stdout.includes('"permissionDecision":"ask"') ? 'ask' : 'silent';
}

test('push forms ask (second-round review B14)', () => {
  for (const c of ['git push', 'git -C . push origin develop', 'git -c a=b push', 'git -P push', 'git.exe push',
    '"git" push', 'git --work-tree . push', 'cd repo && git push -u origin feature/x']) {
    assert.equal(decision(c), 'ask', c);
  }
});

test('third-round review B23: quoted -C path, inline alias, git.cmd and gh pr create/merge ask', () => {
  for (const c of ['git -C "C:/My Project" push', "git -C 'C:/My Project' push origin main",
    'git -c alias.p=push p', 'git -c "alias.p=!git push" p', 'git.cmd push', 'gh pr create --fill',
    'gh pr merge 12 --squash']) {
    assert.equal(decision(c), 'ask', c);
  }
});

test('a very long command still asks (SIGPIPE regression, B14)', () => {
  assert.equal(decision('git push\n#' + 'a'.repeat(300000)), 'ask');
});

test('non-push commands stay silent', () => {
  for (const c of ['git status', 'git stash push', 'npm run push', 'git log --grep push',
    'git commit -m "push the fix"', 'gh pr view 12', 'echo git push later > notes.txt']) {
    const expected = c.startsWith('echo') ? 'ask' : 'silent'; // "git push" inside echo text: fail-safe ask
    assert.equal(decision(c), expected, c);
  }
});

test('malformed hook input is silent, not a crash', () => {
  const r = spawnSync('bash', [HOOK], { input: 'not json', env: { ...process.env, CLAUDE_PROJECT_DIR: PROJECT },
    encoding: 'utf8' });
  assert.equal(r.status, 0);
  assert.equal(r.stdout, '');
});
