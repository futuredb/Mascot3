// Native Android acceptance is invoked only by the local Codex preview button.
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const output = path.join(root, 'output/overlay-current');
const heroes = {akim:'9f226b89-df35-4d3a-9b36-96c28ea1bb62',mars:'a83e9d30-1270-40e6-902c-b44774e84c17',una:'716cdb43-703b-4d7e-8a79-27f03d92b99c'};
fs.mkdirSync(output, { recursive: true });
let state = { status: 'idle' };
async function check() {
  state = { status: 'running', message: 'Проверяем Android-окна и собираем APK…' };
  // Existing video frames only, prepared by the preview for native screenshot fixtures.
  await Promise.all(Object.entries(heroes).map(([name,id]) => new Promise((resolve,reject) => {
    const child = spawn('/opt/homebrew/bin/ffmpeg', ['-v','error','-y','-i',
      path.join(root,'android/app/src/main/assets/bundled-heroes',id,'videos/idle.mp4'),
      '-frames:v','1','-vf','scale=320:320:force_original_aspect_ratio=decrease',
      path.join(output,`${name}-source.png`)], {stdio:'ignore'});
    child.on('error',reject); child.on('exit',code=>code===0?resolve():reject(new Error('Video fixture extraction failed')));
  })));
  async function localProcess(command, args, cwd) {
    return new Promise(resolve => {
      const log = fs.openSync(path.join(output, 'helios-check.log'), 'a');
      const child = spawn(command, args, { cwd, stdio: ['ignore', log, log] });
      child.on('error', () => { fs.closeSync(log); resolve(-1); });
      child.on('exit', code => { fs.closeSync(log); resolve(code); });
    });
  }
  const chatDir = path.join(root, 'live-chat');
  if (await localProcess('npm', ['run', 'build'], chatDir) !== 0 ||
      await localProcess('node', ['--test', 'helios-merged.test.mjs', 'helios-conditioning.test.mjs', 'test/clip-archive.test.mjs'], chatDir) !== 0) {
    state = { status: 'failed', message: 'Не прошла локальная проверка чата Helios' };
    return;
  }
  const env = { ...process.env,
    ...JSON.parse(fs.readFileSync(path.join(root, 'output/phone-interactions/build-environment.json'))),
    JAVA_HOME: '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home',
    MASCOT3_OVERLAY_OUTPUT: output };
  const log = fs.openSync(path.join(output, 'build.log'), 'w');
  const testOnly = process.env.MASCOT3_OVERLAY_TEST_ONLY === '1';
  const code = await new Promise(resolve => {
    const child = spawn('./gradlew', [':app:testUpdateCompatibleUnitTest', '--tests', '*overlay.OverlayTest', '--tests', '*widget.WidgetOverlayTest', '--tests', '*ui.ChatHistoryStoreTest', '--tests', '*ui.ChatActivityPolicyTest', '--tests', '*ui.ChatCalendarTest', '--tests', '*ui.ChatConnectionIntentTest', '--tests', '*data.BundledGenerationRouteTest', '--tests', '*data.FalVideoProviderTest', '--tests', '*data.OpenRouterSettingsStoreTest', '--tests', '*data.LightingPreparationTest', '--tests', '*widget.WidgetDepthRendererTest', '--tests', '*physics.PhysicsSolverTest', '--tests', '*physics.LocalDepthPackTest', '--tests', '*physics.ReliefSurfaceTest', '--tests', '*ui.DepthActivationTest', '--tests', '*ui.HeliosAutoStartTest',
      ...(testOnly ? [] : [':app:assembleUpdateCompatible', ':app:lintUpdateCompatible']), '-I', path.join(root, 'scripts/overlay-preview-init.gradle'), '-Pkotlin.compiler.execution.strategy=in-process',
      '--max-workers=2', '--console=plain'], { cwd: path.join(root, 'android'), env, stdio: ['ignore', log, log] });
    child.on('error', () => resolve(-1)); child.on('exit', resolve);
  });
  fs.closeSync(log);
  const suites = ['overlay.OverlayTest', 'widget.WidgetOverlayTest', 'ui.ChatHistoryStoreTest', 'ui.ChatActivityPolicyTest', 'ui.ChatCalendarTest', 'ui.ChatConnectionIntentTest', 'data.BundledGenerationRouteTest', 'data.FalVideoProviderTest', 'data.OpenRouterSettingsStoreTest', 'data.LightingPreparationTest', 'widget.WidgetDepthRendererTest', 'physics.PhysicsSolverTest', 'physics.LocalDepthPackTest', 'physics.ReliefSurfaceTest', 'ui.DepthActivationTest', 'ui.HeliosAutoStartTest'].map(name => {
    const xml = path.join(root, 'android/build/codex-overlay-app/test-results/testUpdateCompatibleUnitTest/TEST-com.generativemascot.app.'+name+'.xml');
    return fs.existsSync(xml) ? fs.readFileSync(xml, 'utf8').match(/<testsuite[^>]+>/)?.[0] : '';
  });
  const counts = Object.fromEntries(['tests', 'failures', 'errors'].map(k => [k, suites.reduce((n,suite)=>n+Number(suite?.match(new RegExp(k + '="(\\d+)"'))?.[1] || 0),0)]));
  state = { status: code === 0 ? 'passed' : 'failed', code, ...counts,
    message: code === 0 ? (testOnly ? 'Android и Helios: проверки пройдены' : 'Android и Helios: проверки пройдены · APK собран · Lint пройден') : 'Есть ошибка проверки или сборки' };
  fs.writeFileSync(path.join(output, 'validation.json'), JSON.stringify(state, null, 2));
}
http.createServer((req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1:8800');
  const json = (code, data) => { res.writeHead(code, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(data)); };
  if (req.method === 'POST' && url.pathname === '/check') {
    if (req.headers.origin !== 'http://127.0.0.1:8800') return json(403, {});
    if (state.status !== 'running') check().catch(() => { state = { status: 'failed', message: 'Ошибка локальной проверки' }; });
    return json(202, state);
  }
  if (url.pathname === '/state') return json(200, state);
  if (req.method !== 'GET') return json(405, {});
  if (url.pathname === '/') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    return res.end(fs.readFileSync(path.join(root, 'public/overlay-preview.html')));
  }
  const name = url.pathname.slice(1);
  if (name === 'overlay-handle.svg' || name === 'overlay-home.svg') { res.writeHead(200, {'Content-Type':'image/svg+xml'}); return fs.createReadStream(path.join(root, 'design', name)).pipe(res); }
  if (/^widget-overlay-(on|off|restored)\.png$/.test(name) && fs.existsSync(path.join(output, name))) {
    res.writeHead(200, { 'Content-Type': 'image/png', 'Cache-Control': 'no-store' });
    return fs.createReadStream(path.join(output, name)).pipe(res);
  }
  if (/^settings-(akim|mars|una)-(70|100|150)\.png$/.test(name) && fs.existsSync(path.join(output, name))) {
    res.writeHead(200, { 'Content-Type': 'image/png', 'Cache-Control': 'no-store' });
    return fs.createReadStream(path.join(output, name)).pipe(res);
  }
  if (['overlay-settings.png', 'app-rules.json', 'app-rules-settings.png', 'permissions.png'].includes(name) && fs.existsSync(path.join(output, name))) {
    res.writeHead(200, { 'Content-Type': name.endsWith('.json') ? 'application/json' : 'image/png', 'Cache-Control': 'no-store' });
    return fs.createReadStream(path.join(output, name)).pipe(res);
  }
  const clipList = name.match(/^clips\/(akim|mars|una)$/);
  if (clipList) {
    const folder = path.join(root,'android/app/src/main/assets/bundled-heroes',heroes[clipList[1]],'videos');
    return json(200, {reactions:['joyful','greeting','curious','tender','dancing']
      .filter(action=>fs.existsSync(path.join(folder,action+'.mp4')))});
  }
  if (/^(akim|mars|una|una-right|right|landscape)-(70|100|150)(-card\.png|-bell\.png|-empty\.png|-background\.png|-collapsed\.png|-layout\.json|\.png)$/.test(name) && fs.existsSync(path.join(output, name))) {
    res.writeHead(200, { 'Content-Type': name.endsWith('.json')?'application/json':'image/png', 'Cache-Control': 'no-store' });
    return fs.createReadStream(path.join(output, name)).pipe(res);
  }
  const match = name.match(/^(akim|mars|una)(?:\/(idle|joyful|greeting|curious|tender|dancing))?\.mp4$/);
  if (match) {
    const file = path.join(root,'android/app/src/main/assets/bundled-heroes',heroes[match[1]],'videos',(match[2]||'idle')+'.mp4');
    if (!fs.existsSync(file)) return json(404,{});
    const size = fs.statSync(file).size;
    const range = req.headers.range?.match(/^bytes=(\d+)-(\d*)$/);
    const start = range ? Number(range[1]) : 0, end = range && range[2] ? Math.min(Number(range[2]),size-1) : size-1;
    if (start > end || start >= size) { res.writeHead(416,{'Content-Range':`bytes */${size}`}); return res.end(); }
    res.writeHead(range?206:200,{'Content-Type':'video/mp4','Accept-Ranges':'bytes','Content-Length':end-start+1,
      ...(range?{'Content-Range':`bytes ${start}-${end}/${size}`}:{})});
    return fs.createReadStream(file,{start,end}).pipe(res);
  }
  json(404, {});
}).listen(8800, '127.0.0.1', () => console.log('Overlay preview: http://127.0.0.1:8800'));
