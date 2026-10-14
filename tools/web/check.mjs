// Static checks on the web tracker (web/) before it is published, and on
// every pull request. The tracker is how people who never install the app,
// often elderly parents, follow a journey: it must never be published
// broken, and never replaced by anything else (marketing lives in the
// README, not here). No dependencies: plain Node.
//
//   node tools/web/check.mjs            the page, its files and its scripts
//   node tools/web/check.mjs --config   also config.js (after the Pages workflow writes it)
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';

const WEB = path.resolve(process.argv.find(a => a.startsWith('--web='))?.slice(6) ?? 'web');
const withConfig = process.argv.includes('--config');
const problems = [];
const fail = (m) => problems.push(m);
const read = (p) => fs.readFileSync(path.join(WEB, p), 'utf8');

// 1. It is the tracker, not some other page.
if (!fs.existsSync(path.join(WEB, 'index.html'))) fail('web/index.html is missing');
else {
  const html = read('index.html');
  if (!/<title>[^<]*follow a journey[^<]*<\/title>/i.test(html)) fail('web/index.html is not the journey tracker (its <title> must say "follow a journey")');
  // 2. Every local file it loads is there.
  const refs = [...html.matchAll(/(?:src|href)="([^"#?]+)(?:[?#][^"]*)?"/g)].map(m => m[1])
    .filter(r => !/^(https?:|data:|mailto:|tel:|\/\/)/.test(r) && r !== '' && !r.startsWith('#'));
  for (const r of new Set(refs)) {
    if (!fs.existsSync(path.join(WEB, r))) fail(`web/index.html loads "${r}", which is not in web/`);
  }
  for (const must of ['app.js', 'map3d.js', 'config.js', 'styles.css']) {
    if (!refs.includes(must)) fail(`web/index.html no longer loads ${must}`);
  }
}

// 3. Every script parses.
for (const f of fs.readdirSync(WEB).filter(f => f.endsWith('.js'))) {
  try { new vm.Script(read(f), { filename: f }); } catch (e) { fail(`web/${f} does not parse: ${e.message}`); }
}

// 4. The JSON it reads is JSON.
for (const f of ['manifest.webmanifest', 'data/transit/index.json']) {
  if (!fs.existsSync(path.join(WEB, f))) { fail(`web/${f} is missing`); continue; }
  try { JSON.parse(read(f)); } catch (e) { fail(`web/${f} is not valid JSON: ${e.message}`); }
}

// 5. It can reach the backend: without these, every follower sees an empty page.
if (withConfig) {
  const cfg = read('config.js');
  const url = cfg.match(/SUPABASE_URL:\s*"([^"]*)"/)?.[1] ?? '';
  const key = cfg.match(/SUPABASE_ANON_KEY:\s*"([^"]*)"/)?.[1] ?? '';
  if (!/^https:\/\/[a-z0-9-]+\.supabase\.co$/i.test(url)) fail(`config.js has no usable SUPABASE_URL ("${url}"): check apps/android/supabase.properties`);
  if (key.length < 40) fail('config.js has no usable SUPABASE_ANON_KEY: check apps/android/supabase.properties');
}

if (problems.length) {
  console.error('Web tracker check FAILED:\n- ' + problems.join('\n- '));
  process.exit(1);
}
console.log(`Web tracker check passed (${WEB})`);
