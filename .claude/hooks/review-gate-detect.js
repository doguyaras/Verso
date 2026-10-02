#!/usr/bin/env node
'use strict';
// Push detection for review-gate.sh (Bash or PowerShell tool; ADR-0007 #25). Reads the hook JSON on stdin and prints
// "push" when the command publishes commits, nothing otherwise. A convenience gate, not a guarantee: CI is the
// enforcing layer (reference 19.6). When in doubt it says "push" (fail-safe: the user is only asked).
//
// History: the bash regex stripped quotes, so `git -C "C:/My Project" push` split the path and passed; aliases
// defined inline (`git -c alias.p=push p`), git.cmd and `gh pr create` passed as well (third-round review B23). The
// earlier `printf | grep -q` form lost long commands to SIGPIPE (second-round review B14). Node reads stdin fully.

const GIT = String.raw`(?:^|[^\w-])git(?:\.exe|\.cmd)?`;
// Between git and the subcommand: -C/-c with a value, short flags, --long[=value] and --long value.
const GLOBAL_OPTS = String.raw`(?:\s+-[Cc]\s+\S+|\s+-[A-Za-z]+|\s+--[\w-]+(?:=\S+)?(?:\s+[^-\s]\S*)?)*`;
const PUSH = new RegExp(GIT + GLOBAL_OPTS + String.raw`\s+push(?:\s|$)`);
// An alias defined on the command line can expand to anything: ask.
const INLINE_ALIAS = new RegExp(GIT + String.raw`\s.*?-c\s*["']?alias\.`);
// Opening or merging a pull request publishes as well.
const GH_PR = /(?:^|[^\w-])gh(?:\.exe)?\s+pr\s+(?:create|merge)(?:\s|$)/;

function normalize(command) {
  return command
    // "git" / 'git.exe' written with quotes is still git
    .replace(/(["'])(git(?:\.exe|\.cmd)?|gh(?:\.exe)?)\1/g, '$2')
    // any other quoted value becomes one token, so a path with spaces stays a single -C value
    .replace(/"(?:[^"\\]|\\.)*"|'[^']*'/g, 'Q');
}

function isPush(command) {
  const raw = String(command || '');
  if (INLINE_ALIAS.test(raw)) return true;
  const cmd = normalize(raw);
  return PUSH.test(cmd) || GH_PR.test(cmd);
}

module.exports = { isPush, normalize };

if (require.main === module) {
  let input = '';
  process.stdin.on('data', (c) => { input += c; }).on('end', () => {
    let command = '';
    try {
      const json = JSON.parse(input);
      command = (json.tool_input && json.tool_input.command) || '';
    } catch {
      command = '';
    }
    if (isPush(command)) process.stdout.write('push');
  });
}
