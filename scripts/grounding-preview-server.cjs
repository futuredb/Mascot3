// Android checks and local rendering are started from the Codex preview only.
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const {spawn} = require('node:child_process');
const root = path.resolve(__dirname, '..');
const output = path.join(root, 'output/grounding-0.1.95');
fs.mkdirSync(output, {recursive:true});
let state = {status:'idle', message:'Проверка ещё не запускалась'};
async function check() {
  state = {status:'running', message:'Проверяем привязку ног, рендерим сравнение и собираем APK…'};
  const env = {...process.env,
    ...JSON.parse(fs.readFileSync(path.join(root,'output/phone-interactions/build-environment.json'))),
    JAVA_HOME:'/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home',
    MASCOT3_GROUNDING_OUTPUT:output};
  const log = fs.openSync(path.join(output,'build.log'),'w');
  const code = await new Promise(resolve => {
    const child = spawn('./gradlew', [':app:testUpdateCompatibleUnitTest', '--tests', '*HeroGroundingTest',
      '--tests', '*PhysicsFramingTest', ':app:assembleUpdateCompatible', ':app:lintUpdateCompatible',
      '-Pkotlin.compiler.execution.strategy=in-process','--max-workers=2','--console=plain'],
      {cwd:path.join(root,'android'),env,stdio:['ignore',log,log]});
    child.on('error',()=>resolve(-1)); child.on('exit',resolve);
  });
  fs.closeSync(log);
  state = {status:code===0?'passed':'failed',code,
    message:code===0?'Привязка проверена · APK собран · Lint пройден':'Проверка или сборка требует исправления',
    comparison:fs.existsSync(path.join(output,'comparison.png'))};
  fs.writeFileSync(path.join(output,'validation.json'),JSON.stringify(state,null,2));
}
const html = `<!doctype html><meta charset="utf-8"><title>Маскот 3 · линия пола</title>
<style>body{font:16px/1.6 system-ui;margin:32px auto;max-width:1200px;padding:0 24px;background:#f7f6fa;color:#332c46}h1{font-size:28px}button{font:inherit;background:#635180;color:white;border:0;border-radius:12px;padding:12px 20px;cursor:pointer}button:disabled{opacity:.5}img{width:100%;border-radius:16px}p{max-width:850px}#status{margin:20px 0}a{color:#635180}</style>
<h1>Маскот 3 · персонажи на линии пола</h1>
<p>Слева — исходный кадр без выравнивания. В центре — привязка по ногам. Справа — та же картинка с увеличенными прозрачными полями. Размер персонажа сохраняется. Уна служит ориентиром; её существующий физический пакет остаётся прежним.</p>
<button id="check">Проверить и собрать</button><p id="status"></p><img id="comparison" alt="Сравнение положения Уны, Акима, Марса, Ланиса и Ринка" hidden>
<script>let shown=false;document.querySelector('#check').onclick=async()=>{shown=false;await fetch('/check',{method:'POST'});update()};async function update(){const s=await(await fetch('/state')).json();document.querySelector('#status').textContent=s.message;document.querySelector('#check').disabled=s.status==='running';if(s.comparison&&!shown){const img=document.querySelector('#comparison');img.src='/comparison.png?t='+Date.now();img.hidden=false;shown=true}}update();setInterval(update,2000)</script>`;
http.createServer((req,res)=>{
  const url=new URL(req.url,'http://127.0.0.1:8817');
  const json=(code,data)=>{res.writeHead(code,{'Content-Type':'application/json','Cache-Control':'no-store'});res.end(JSON.stringify(data))};
  if(req.method==='POST'&&url.pathname==='/check') {
    if(req.headers.origin!=='http://127.0.0.1:8817')return json(403,{});
    if(state.status!=='running')check().catch(()=>{state={status:'failed',message:'Ошибка локальной проверки'}});
    return json(202,state);
  }
  if(req.method!=='GET')return json(405,{});
  if(url.pathname==='/state')return json(200,state);
  if(url.pathname==='/'){res.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});return res.end(html)}
  if(url.pathname==='/comparison.png'&&fs.existsSync(path.join(output,'comparison.png'))){res.writeHead(200,{'Content-Type':'image/png','Cache-Control':'no-store'});return fs.createReadStream(path.join(output,'comparison.png')).pipe(res)}
  json(404,{});
}).listen(8817,'127.0.0.1',()=>console.log('Codex grounding preview: http://127.0.0.1:8817'));
