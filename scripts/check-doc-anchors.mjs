#!/usr/bin/env node
/**
 * Verifies that every "#anchor" in the committed documentation points at a heading that exists.
 *
 * markdown-link-check (ci.yml's other docs step) deliberately skips same-file fragments — it treats
 * "#foo" as a URL it cannot resolve and would report every one as a 404. That leaves this
 * repository's most load-bearing navigation unverified: roadmap.md's phase index and
 * improvement-plan.md's priority table are both entirely anchor links, and a renamed heading breaks
 * them silently — the reader lands at the top of the page with no error anywhere.
 *
 * Plain Node, no dependencies, so it runs identically locally and on a runner.
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname, normalize, relative } from 'node:path';

const ROOT = new URL('..', import.meta.url).pathname;

// The corpus is fetched at run time by scripts/fetch-corpus.sh, not committed — its documents are
// third-party content whose internal links point at the upstream repository's own layout.
const SKIP = new Set(['node_modules', 'target', '.git', '.claude', 'corpus']);

function markdownFiles(dir = ROOT, out = []) {
  for (const entry of readdirSync(dir)) {
    if (SKIP.has(entry)) continue;
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) markdownFiles(full, out);
    else if (entry.endsWith('.md')) out.push(full);
  }
  return out;
}

// GitHub's heading-anchor slug: lowercase, drop anything that is not a letter/number/space/hyphen
// (so backticks, periods, slashes and parentheses vanish), then spaces to hyphens.
function headingSlugs(text) {
  return new Set(
    text
      .split('\n')
      .filter((line) => /^#{1,6} /.test(line))
      .map((line) =>
        line
          .replace(/^#{1,6} /, '')
          .trim()
          .toLowerCase()
          .replace(/[^a-z0-9 -]/g, '')
          .replace(/ /g, '-')
      )
  );
}

const slugCache = new Map();
const slugsOf = (file) => {
  if (!slugCache.has(file)) slugCache.set(file, headingSlugs(readFileSync(file, 'utf8')));
  return slugCache.get(file);
};

let failures = 0;
for (const file of markdownFiles()) {
  const text = readFileSync(file, 'utf8');
  for (const [, link] of text.matchAll(/\]\(([^)]+)\)/g)) {
    if (/^https?:/.test(link)) continue;
    const [pathPart, anchor] = link.split('#');
    if (!anchor) continue;

    const target = pathPart ? normalize(join(dirname(file), pathPart)) : file;
    if (!target.endsWith('.md')) continue;
    try {
      if (!slugsOf(target).has(anchor)) {
        console.error(
          `FAIL: ${relative(ROOT, file)}: "${link}" -> no heading "#${anchor}" in ${relative(ROOT, target)}`
        );
        failures++;
      }
    } catch {
      // A missing target file is markdown-link-check's job to report, not this script's.
    }
  }
}

if (failures > 0) {
  console.error(`\n${failures} broken anchor link(s).`);
  process.exit(1);
}
console.log('All documentation anchors resolve to a real heading.');
