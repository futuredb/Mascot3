// Build and offline validation are started only from the Codex preview.
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const {spawn} = require('node:child_process');
const root = path.resolve(__dirname, '..');
const output = path.join(root, 'output/helios-glitches');
fs.mkdirSync(output, {recursive:true});
let state = {status:'idle', message:'Проверка ещё не запускалась'};
const port=8825;
async function validate() {
  state={status:'running', message:'Сборка основного чата, проверка режима общения и Helios…'};
  const run=(cmd,args,cwd,env=process.env)=>new Promise(resolve=>{
    const log=fs.openSync(path.join(output,'build.log'),'a');
    const child=spawn(cmd,args,{cwd,env,stdio:['ignore',log,log]});
    child.on('error',()=>{fs.closeSync(log);resolve(-1)});
    child.on('exit',code=>{fs.closeSync(log);resolve(code)});
  });
  const chat=path.join(root,'live-chat');
  if(await run('npm',['run','build'],chat)!==0)throw Error('Не собрался плеер Helios');
  const {runServerChecks}=await import('../live-chat/qa-server.mjs');
  const checks=await runServerChecks();
  fs.writeFileSync(path.join(output,'helios-checks.json'),JSON.stringify(checks,null,2));
  const failed=checks.results.filter(r=>!r.passed);
  if(failed.length)throw Error(failed.map(r=>r.name+': '+r.error).join('; '));
  state.message='Helios: '+checks.results.length+' проверок пройдено. Собирается основной APK…';
  const env={...process.env,...JSON.parse(fs.readFileSync(path.join(root,'output/phone-interactions/build-environment.json'))),
    JAVA_HOME:'/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'};
  const code=await run('./gradlew',[':app:assembleUpdateCompatible',':app:lintUpdateCompatible',':app:testUpdateCompatibleUnitTest',
    '--tests','*ChatHistoryStoreTest','--tests','*ChatActivityPolicyTest','--tests','*ChatCalendarTest',
    '--tests','*ChatConnectionIntentTest','--tests','*HeliosAutoStartTest','--tests','*ChatIdleFramingTest','--tests','*ChatProfanityTest',
    '-Pkotlin.compiler.execution.strategy=in-process','--max-workers=2','--console=plain'],path.join(root,'android'),env);
  if(code!==0)throw Error('Сборка Android или проверка завершилась с ошибкой.');
  const report=path.join(root,'android/app/build/test-results/testUpdateCompatibleUnitTest/TEST-com.generativemascot.app.ui.ChatProfanityTest.xml');
  const xml=fs.readFileSync(report,'utf8');
  const languageTests=Number(xml.match(/<testsuite[^>]* tests="(\d+)"/)?.[1]||0);
  if(languageTests!==8||!/<testsuite[^>]* failures="0"/.test(xml)||!/<testsuite[^>]* errors="0"/.test(xml))throw Error('Не прошла проверка постоянного режима общения.');
  state={status:'passed',message:'Режим общения: '+languageTests+' проверок · Helios: '+checks.results.length+' проверок · APK собран · Android и Lint пройдены',externalCalls:0};
  fs.writeFileSync(path.join(output,'validation.json'),JSON.stringify(state,null,2));
}
http.createServer((req,res)=>{
  const json=(status,data)=>{res.writeHead(status,{'Content-Type':'application/json','Cache-Control':'no-store'});res.end(JSON.stringify(data))};
  if(!['127.0.0.1:'+port,'localhost:'+port].includes(req.headers.host))return json(403,{});
  if(req.url==='/validate'&&req.method==='POST'){
    if(req.headers.origin!=='http://'+req.headers.host)return json(403,{});
    if(state.status!=='running')validate().catch(error=>{state={status:'failed',message:error.message};fs.writeFileSync(path.join(output,'validation.json'),JSON.stringify(state))});
    return json(202,state);
  }
  if(req.method!=='GET')return json(405,{});
  if(req.url==='/state')return json(200,state);
  const routes={
    '/':['public/helios-glitches/index.html','text/html; charset=utf-8'],
    '/helios-archive.webm':['output/hero-backups/2026-11-05/bundled-heroes/9f226b89-df35-4d3a-9b36-96c28ea1bb62/chat-animations/5ca6fd30d51348e9a2160fe6948ebb8e.webm','video/webm'],
    '/dialogue':['public/helios-glitches/dialogue.html','text/html; charset=utf-8'],
    '/language':['public/helios-glitches/language.html','text/html; charset=utf-8'],
    '/preview.js':['public/helios-glitches/preview.js','text/javascript'],
    '/chat-glitch.js':['live-chat/src/chat-glitch.js','text/javascript'],
    '/replay-transition.js':['live-chat/src/replay-transition.js','text/javascript'],
    '/hero-una.png':['android/app/src/main/assets/bundled-heroes/716cdb43-703b-4d7e-8a79-27f03d92b99c/base.png','image/png'],
    '/hero-akim.png':['android/app/src/main/assets/bundled-heroes/9f226b89-df35-4d3a-9b36-96c28ea1bb62/base.png','image/png'],
    '/hero-mars.png':['android/app/src/main/assets/bundled-heroes/a83e9d30-1270-40e6-902c-b44774e84c17/base.png','image/png'],
    '/idle.mp4':['android/app/src/main/assets/bundled-heroes/9f226b89-df35-4d3a-9b36-96c28ea1bb62/videos/idle.mp4','video/mp4'],
    '/joyful.mp4':['android/app/src/main/assets/bundled-heroes/9f226b89-df35-4d3a-9b36-96c28ea1bb62/videos/joyful.mp4','video/mp4'],
  };
  const clip = /^\/dialogue-media\/([0-9a-f-]{36})\/(listening|speaking)\.mp4$/.exec(req.url);
  const route = clip
    ? [`output/chat-dialogue-loops/${clip[1]}/chat-dialogue-loops-v1/${clip[2]}/loop.mp4`, 'video/mp4']
    : Object.hasOwn(routes,req.url)&&routes[req.url];
  if(!route)return json(404,{});
  const file=path.join(root,route[0]);
  if(!fs.existsSync(file))return json(404,{});
  const size=fs.statSync(file).size;
  const range=/^bytes=(\d+)-(\d*)$/.exec(req.headers.range||'');
  const start=range?Number(range[1]):0, end=range?.[2]?Math.min(Number(range[2]),size-1):size-1;
  if(start>end)return json(416,{});
  res.writeHead(range?206:200,{'Content-Type':route[1],'Cache-Control':'no-store','Content-Length':end-start+1,
    'Accept-Ranges':'bytes',...(range?{'Content-Range':`bytes ${start}-${end}/${size}`}:{})});
  fs.createReadStream(file,{start,end}).pipe(res);
}).listen(port,'127.0.0.1',()=>console.log('Helios glitches preview: http://127.0.0.1:'+port));
