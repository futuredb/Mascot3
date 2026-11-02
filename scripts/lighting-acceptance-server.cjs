// Local Codex preview. No generator imports or paid credentials in media jobs.
const http = require('node:http');
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { LightingJobs } = require('../lighting/jobs');
const { handleLighting } = require('../lighting/routes');
const root = path.resolve(__dirname, '..');
const out = path.join(root, 'output/automatic-lighting-0.1.60');
fs.mkdirSync(out, { recursive: true });
const jobs = new LightingJobs({ root: path.join(out, 'jobs'), env: { PATH: process.env.PATH, HOME: process.env.HOME,
  LIGHTING_PYTHON: path.join(root, 'output/local-depth/venv/bin/python') } });
const token = 'codex-local-preview';
const heroes = [['akim', '9f226b89-df35-4d3a-9b36-96c28ea1bb62'], ['mars', 'a83e9d30-1270-40e6-902c-b44774e84c17'], ['una', '716cdb43-703b-4d7e-8a79-27f03d92b99c']];
const fixtures = Object.fromEntries(heroes.flatMap(([name, id]) => [
  [`${name}.png`, path.join(root, 'android/app/src/main/assets/bundled-heroes', id, 'base.png')],
  [`${name}.mp4`, path.join(root, 'android/app/src/main/assets/bundled-heroes', id, 'videos/idle.mp4')],
]));
let state = fs.existsSync(path.join(out, 'checks.json'))
  ? JSON.parse(fs.readFileSync(path.join(out, 'checks.json'), 'utf8')) : { status: 'idle' };
let production = { status: 'idle' };
const send = (res, code, value) => { res.writeHead(code, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(value)); };
const serveFile = (res, folder, name) => {
  const file = path.join(folder, name); res.writeHead(200, { 'Content-Type': name.endsWith('.png') ? 'image/png' : name.endsWith('.mp4') ? 'video/mp4' : 'application/json' });
  fs.createReadStream(file).pipe(res);
};
function run(command, args, cwd, env, logName) {
  return new Promise(resolve => {
    const log = fs.openSync(path.join(out, logName), 'w');
    const child = spawn(command, args, { cwd, env, stdio: ['ignore', log, log] });
    child.on('error', () => { fs.closeSync(log); resolve(-1); });
    child.on('exit', code => { fs.closeSync(log); resolve(code); });
  });
}
async function check() {
  state = { status: 'running', stage: 'Проверка очереди, промптов и восстановления' };
  const env = { ...process.env, OPENROUTER_API_KEY: '', REPLICATE_API_TOKEN: '' };
  const tests = ['lighting/jobs.test.js', ...fs.readdirSync(path.join(root, 'pipeline')).filter(f => f.endsWith('.test.js')).map(f => 'pipeline/' + f)];
  const node = await run(process.execPath, ['--test', ...tests], root, env, 'node-tests.log');
  state.stage = 'Проверка установки карт и Android-рендера';
  const buildEnv = { ...env, ...JSON.parse(fs.readFileSync(path.join(root, 'output/phone-interactions/build-environment.json'))),
    JAVA_HOME: '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home' };
  const filters = ['LightingPreparationTest', 'CreativePromptContractsTest', 'PhoneInteractionMotionTest', 'ServerConnectionTest', 'HeroStartupTest', 'StateResolverTest', 'RelativeOrientationTest', 'PhysicsSolverTest', 'HeroRestoreSelectionTest', 'LocalDepthPackTest', 'PhysicsStoreTest', 'ReliefSurfaceTest', 'TiltLightingTest', 'VideoLightingTest', 'VideoBackgroundKeyTest'];
  const android = await run('./gradlew', [':app:testUpdateCompatibleUnitTest', ...filters.flatMap(f => ['--tests', '*' + f]),
    '-Pkotlin.compiler.execution.strategy=in-process', '--max-workers=1', '--console=plain'], path.join(root, 'android'), buildEnv, 'android-tests.log');
  state = { status: node === 0 && android === 0 ? 'passed' : 'failed', node, android };
  await fsp.writeFile(path.join(out, 'checks.json'), JSON.stringify(state, null, 2));
}
const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://127.0.0.1:8799');
    if (req.method !== 'GET' && req.headers.origin !== 'http://127.0.0.1:8799') return send(res, 403, { error: 'origin' });
    if (url.pathname.startsWith('/v1/lighting/')) return await handleLighting({ req, res, url, lightingJobs: jobs, token, send, serveFile });
    if (url.pathname === '/check' && req.method === 'POST') { if (state.status !== 'running') check().catch(e => { state = { status: 'failed', error: e.message }; }); return send(res, 202, state); }
    if (url.pathname === '/state') return send(res, 200, state);
    if (url.pathname === '/production-state') return send(res, 200, production);
    if (url.pathname === '/production-check' && req.method === 'POST') {
      if (production.status !== 'running') {
        production = { status: 'running', stage: 'Проверяем сервер' };
        require('./lighting-production-check.cjs').verify(value => { production = value; })
          .catch(error => { production = { status: 'failed', error: error.message }; });
      }
      return send(res, 202, production);
    }
    if (url.pathname.startsWith('/fixtures/')) {
      const file = fixtures[url.pathname.slice(10)]; if (!file) return send(res, 404, {});
      return serveFile(res, path.dirname(file), path.basename(file));
    }
    if (url.pathname === '/') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      return res.end(fs.readFileSync(path.join(root, 'public/lighting-preparation.html')));
    }
    send(res, 404, {});
  } catch (e) { send(res, e.status || 500, { error: e.message }); }
});
server.listen(8799, '127.0.0.1', () => { console.log('Lighting preparation preview: http://127.0.0.1:8799'); jobs.resumeInterrupted(); });
