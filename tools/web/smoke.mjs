// Opens the web tracker in a headless browser, the way a follower does, and
// fails on any script error: with no link, with a link that is no longer
// valid, and on a phone-sized screen. Needs playwright (installed by CI).
//
//   node tools/web/smoke.mjs [--web=web]
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(path.resolve('package.json'));
const { chromium } = require('playwright');
const WEB = path.resolve(process.argv.find(a => a.startsWith('--web='))?.slice(6) ?? 'web');
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json',
  '.webmanifest': 'application/manifest+json', '.png': 'image/png', '.webp': 'image/webp', '.svg': 'image/svg+xml', '.txt': 'text/plain' };

const server = http.createServer((req, res) => {
  const p = decodeURIComponent(new URL(req.url, 'http://x').pathname).replace(/^\/Koode\//, '/');
  const file = path.join(WEB, p.endsWith('/') ? p + 'index.html' : p);
  if (!file.startsWith(WEB) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'content-type': TYPES[path.extname(file)] ?? 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${server.address().port}/Koode/`;

const browser = await chromium.launch();
const errors = [];
const visits = [
  ['no link', base, { width: 1280, height: 800 }],
  ['an old link', base + '#XXXXXX-0000', { width: 390, height: 844 }],
];
for (const [what, url, viewport] of visits) {
  const page = await browser.newPage({ viewport });
  page.on('pageerror', e => errors.push(`${what}: ${e.message}`));
  await page.goto(url, { waitUntil: 'load', timeout: 60_000 });
  await page.waitForTimeout(4_000);
  const text = (await page.locator('body').innerText()).trim();
  if (text.length < 20) errors.push(`${what}: the page is blank`);
  await page.close();
}
await browser.close();
server.close();
if (errors.length) { console.error('Web tracker smoke test FAILED:\n- ' + errors.join('\n- ')); process.exit(1); }
console.log('Web tracker smoke test passed');
