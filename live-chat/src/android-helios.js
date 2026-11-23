import {createReplayTransition} from './replay-transition.js';
import {createPortraitTransition} from './chat-glitch.js';
import {hasVisibleDetail} from './frame-visibility.js';
import {HeliosModel} from '@reactor-models/helios';
import {createClipArchive} from './clip-archive.js';
import {conditionMascot, updateMascotPrompt, IDENTITY_REFRESH_MS} from './helios-conditioning.js';

const liveVideo=document.querySelector('#video'), replayVideo=document.querySelector('#replay');
const frame=document.querySelector('#frame'), frameCtx=frame.getContext('2d');
const glitchSearch=String(globalThis.location?.search||'');
const glitchParam=name=>{const match=new RegExp(`[?&]${name}=([^&]*)`).exec(glitchSearch);try{return match?decodeURIComponent(match[1]):''}catch{return''}};
const safeGlitchColor=value=>/^#[0-9a-f]{6}$/i.test(value||'')?value:'#AAA0FF';
const finiteChatCapture=glitchParam('finite')==='1';
const glitchPalette={warm:safeGlitchColor(glitchParam('warm')||'#FF806B'),cool:safeGlitchColor(glitchParam('cool')||'#AAA0FF')};
let glitchEnabled=false;
const liveTransition=createPortraitTransition(frame,frameCtx,{palette:glitchPalette,enabled:()=>glitchEnabled});
let promptTransitionPending=false, promptFrameReady=false;
const sample=document.createElement('canvas');sample.width=96;sample.height=54;
const sampleCtx=sample.getContext('2d',{willReadFrequently:true});
let model=null, reference=null, timer=null, firstFrameTimer=null, generation=0, starting=false, pendingAction=null;
let displayVisible=true;
let liveVisible=false, clipRecorder=null, latestClip=null, replayUrl=null, replayGeneration=0, liveTrack=null, actionVersion=0, recorderEpoch=0;
let recordingContext='', recorderTimer=null;
let actionQueue=Promise.resolve();
let prewarmActive=false, prewarmTimer=null, stopping=null, saveIdleOnStop=false;
let identityTimer=null, lastCondition=null;
const REACTION_REFRESH_DELAY_MS=30_000;
const LOOP_CAPTURE_MS=30_000;
let warmTimer=null, warmPause=null, pausedForReplay=false, resetInProgress=false, resetFrameReady=false, startupTask=null;
function scheduleIdentityRefresh(current,id,delay=IDENTITY_REFRESH_MS){
 clearTimeout(identityTimer);
 identityTimer=setTimeout(()=>{
  if(id!==generation||model!==current||!lastCondition)return;
  const version=actionVersion;
  actionQueue=actionQueue.catch(()=>{}).then(async()=>{
   if(id!==generation||model!==current||version!==actionVersion||!lastCondition)return;
   const {action,context,appearance,reply}=lastCondition;
   await updateMascotPrompt(current,action,context,appearance,reply);
   tell('diagnostic','identity-refreshed');
  }).catch(()=>{ if(id===generation&&model===current) { tell('diagnostic','identity-refresh-failed'); tell('status','Обновляем образ персонажа…'); } })
    .finally(()=>{if(id===generation&&model===current&&version===actionVersion)scheduleIdentityRefresh(current,id)});
 },delay);
}
function resizeFrame(){frame.style.width=window.innerWidth+'px';frame.style.height=window.innerHeight+'px'}
resizeFrame();window.addEventListener('resize',resizeFrame);requestAnimationFrame(resizeFrame);
const tell=(type,value='')=>window.MascotBridge?.event(type,String(value));
let perfSample=null;
function measureFrames(kind,now,meta){
 if(!displayVisible)return;
 if(!perfSample||perfSample.kind!==kind){perfSample={kind,start:now,count:0,first:meta.presentedFrames,last:meta.presentedFrames};return}
 perfSample.count++;perfSample.last=meta.presentedFrames;
 const elapsed=now-perfSample.start;
 if(elapsed>=10000){tell('performance',JSON.stringify({kind,seconds:Math.round(elapsed/100)/10,callbacks:perfSample.count,presented:perfSample.last-perfSample.first}));perfSample=null}
}
async function referenceBlob(data){const img=new Image();img.src=data;await img.decode();const c=document.createElement('canvas');c.width=1024;c.height=576;const ctx=c.getContext('2d');ctx.fillStyle='#EAEBEC';ctx.fillRect(0,0,1024,576);const scale=finiteChatCapture?Math.min(340/img.width,345/img.height):Math.min(1024/img.width,460/img.height);ctx.drawImage(img,(1024-img.width*scale)/2,(576-img.height*scale)/2,img.width*scale,img.height*scale);return await new Promise((ok,fail)=>c.toBlob(b=>b?ok(b):fail(new Error('Не удалось подготовить изображение.')),'image/png'))}
function draw(source){
 if(!displayVisible||!source.videoWidth||!source.videoHeight)return;
 const w=window.MascotOverlayMode?Math.min(512,source.videoWidth):source.videoWidth;
 const h=window.MascotOverlayMode?Math.round(source.videoHeight*w/source.videoWidth):source.videoHeight;
 if(frame.width!==w||frame.height!==h){frame.width=w;frame.height=h}
 frameCtx.clearRect(0,0,w,h);
 frameCtx.drawImage(source,0,0,w,h);
 if(window.MascotOverlayMode){
  const image=frameCtx.getImageData(0,0,w,h), a=image.data;
  for(let i=0;i<a.length;i+=4){
   const d=Math.max(Math.abs(a[i]-234),Math.abs(a[i+1]-235),Math.abs(a[i+2]-236));
   if(d<12)a[i+3]=0;else if(d<32)a[i+3]=Math.round(a[i+3]*(d-12)/20);
  }
  frameCtx.putImageData(image,0,0);
 }
}
function stopReplay(){replayGeneration++;replayVideo.pause();replayVideo.removeAttribute('src');replayVideo.load();if(replayUrl)URL.revokeObjectURL(replayUrl);replayUrl=null}
function playReplay(blob){if(!blob?.size)return false;stopReplay();const n=replayGeneration;const transition=createReplayTransition(frame,frameCtx,undefined,glitchPalette,()=>glitchEnabled);replayUrl=URL.createObjectURL(blob);replayVideo.src=replayUrl;replayVideo.loop=true;replayVideo.muted=true;function next(now,meta){if(n!==replayGeneration||liveVisible)return;try{if(displayVisible){measureFrames('replay',now,meta);transition.render(replayVideo,now,meta.mediaTime,draw)}}catch{}replayVideo.requestVideoFrameCallback(next)}replayVideo.requestVideoFrameCallback(next);replayVideo.play().catch(()=>tell('error','Не удалось повторить сохранённый фрагмент.'));return true}
// The WebRTC connection and recording stay active behind the home screen;
// avoid uploading a full video frame to an invisible canvas on every frame.
window.setMascotVisibility=(visible)=>{
 displayVisible=Boolean(visible);
 if(displayVisible){
  if(pausedForReplay&&replayUrl)draw(replayVideo);
  else if(liveVisible)draw(liveVideo);
 }
};
// One recorder per complete response: it starts just before the new prompt and
// stays open until the next prompt or the stream stops. A MediaRecorder result
// is an independent playable WebM, not a concatenation of file fragments.
window.setMascotGlitchEnabled=(enabled)=>{
 glitchEnabled=Boolean(enabled);
 if(!glitchEnabled){
  liveTransition.cancel();
  if(displayVisible)window.setMascotVisibility(true);
 }
};
function saveIdle(blob){if(!window.MascotBridge?.saveIdleClip)return;const reader=new FileReader();reader.onload=()=>{try{if(window.MascotBridge.saveIdleClip(String(reader.result).split(',')[1]))tell('idle_saved')}catch{}};reader.readAsDataURL(blob)}
function startRecorder(track,id,context=''){if(finiteChatCapture&&!context.trim())return;if(!globalThis.MediaRecorder||!track||id!==generation)return;let parts=[];const epoch=++recorderEpoch,saveAsIdle=prewarmActive,version=actionVersion;try{const type=['video/webm;codecs=vp8','video/webm','video/mp4'].find(t=>MediaRecorder.isTypeSupported(t));const rec=new MediaRecorder(new MediaStream([track]),{...(type?{mimeType:type}:{}),videoBitsPerSecond:2000000});const archive=context.trim()?createClipArchive(window.MascotBridge,context,rec.mimeType||type||'',()=>tell('archive-error','Не удалось сохранить реакцию в библиотеку.')):null;latestClip=null;clipRecorder=rec;rec.ondataavailable=e=>{if(e.data.size){parts.push(e.data);archive?.append(e.data)}};rec.onstop=()=>{if(epoch===recorderEpoch&&parts.length){const blob=new Blob(parts,{type:rec.mimeType||'video/webm'});if(blob.size){latestClip=blob;if(saveAsIdle&&saveIdleOnStop)saveIdle(blob)}}void archive?.finish()};rec.start(1000);clearTimeout(recorderTimer);if(!saveAsIdle){recorderTimer=setTimeout(()=>{void (async()=>{await finishRecorder();if(id===generation&&model&&version===actionVersion&&latestClip)await pauseForReplay(model,id,version)})()},finiteChatCapture?8_000:LOOP_CAPTURE_MS)}else{clearTimeout(prewarmTimer);prewarmTimer=setTimeout(()=>void stop('',true,true),6000)}}catch{clipRecorder=null;if(context.trim())tell('archive-error','Не удалось записать реакцию для библиотеки.')}}
function finishRecorder(save=true){clearTimeout(recorderTimer);recorderTimer=null;if(!save)recorderEpoch++;const rec=clipRecorder;clipRecorder=null;if(!rec||rec.state!=='recording')return Promise.resolve();return new Promise(resolve=>{let done=false;const finish=()=>{if(!done){done=true;resolve()}};const original=rec.onstop;rec.onstop=e=>{original?.call(rec,e);finish()};try{rec.stop()}catch{finish()}setTimeout(finish,3000)})}
async function pauseForReplay(current,id,version){
 if(id!==generation||model!==current||version!==actionVersion)return;
 if(finiteChatCapture){
  await stop('',true);
  if(!replayUrl)tell('error','Не удалось повторить сохранённый фрагмент.');
  return;
 }
 pausedForReplay=true;liveVisible=false;
 if(playReplay(latestClip)){tell('replay');tell('diagnostic','replay-live');}
 else {pausedForReplay=false;liveVisible=true;tell('status','Не удалось повторить фрагмент.');}
}
function armTimer(){clearTimeout(timer);timer=null}
function applyAction(current,id,action,context='',appearance='',reply=''){
 const version=++actionVersion;lastCondition={action,context,appearance,reply};armTimer();scheduleIdentityRefresh(current,id,REACTION_REFRESH_DELAY_MS);const next=actionQueue.catch(()=>{}).then(async()=>{
  if(id!==generation||model!==current||version!==actionVersion)return;
  latestClip=null;await finishRecorder(false);if(id!==generation||model!==current||version!==actionVersion)return;
  recordingContext=reply?context:'';if(liveVisible&&liveTrack)startRecorder(liveTrack,id,recordingContext);
  promptTransitionPending=true;promptFrameReady=false;
  await updateMascotPrompt(current,action,context,appearance,reply)
 });actionQueue=next;return next
}
async function performStop(reason='',replay=true,saveIdle=false){
 generation++;liveTransition.cancel();promptTransitionPending=false;promptFrameReady=false;clearTimeout(timer);clearTimeout(firstFrameTimer);clearTimeout(prewarmTimer);clearTimeout(identityTimer);clearTimeout(warmTimer);warmTimer=null;identityTimer=null;lastCondition=null;prewarmTimer=null;
 saveIdleOnStop=saveIdle&&prewarmActive;prewarmActive=false;timer=null;firstFrameTimer=null;
 const old=model, alreadyReplaying=replay&&!!replayUrl&&!liveVisible;model=null;reference=null;starting=false;pendingAction=null;liveTrack=null;pausedForReplay=false;resetInProgress=false;resetFrameReady=false;actionVersion++;actionQueue=Promise.resolve();
 await finishRecorder();saveIdleOnStop=false;liveVisible=false;liveVideo.pause();liveVideo.srcObject=null;
 try{await old?.disconnect()}catch{tell('error','Не удалось подтвердить отключение Helios.');return}
 const replaying=alreadyReplaying||(replay&&playReplay(latestClip));if(replaying){if(!alreadyReplaying)tell('replay')}else if(!replay){stopReplay();latestClip=null;frameCtx.clearRect(0,0,frame.width,frame.height)}
 if(reason)tell('stopped',replay&&!replaying?'Поток остановлен. Не удалось повторить фрагмент.':reason)
}
function stop(reason='',replay=true,saveIdle=false){if(stopping)return stopping;const work=performStop(reason,replay,saveIdle);const pending=work.finally(()=>{if(stopping===pending)stopping=null});stopping=pending;return pending}
let restartRequest=0, restartQueue=Promise.resolve(), restartFramePending=false, waitingForResetChunk=false;
window.stopMascot=()=>{restartRequest++;return stop('Поток остановлен.')};window.closeMascot=()=>{restartRequest++;restartFramePending=false;return stop('',false)};
window.restartMascot=(data,action,context='',appearance='',reply='')=>{
 const request=++restartRequest;
 restartQueue=restartQueue.catch(()=>{}).then(async()=>{
  if(request!==restartRequest)return;
  if(startupTask)await startupTask.catch(()=>{});
  if(warmPause)await warmPause;
  if(request!==restartRequest)return;
  const current=model,id=generation;
  if(current&&reference){
   const version=++actionVersion;clearTimeout(identityTimer);clearTimeout(warmTimer);clearTimeout(firstFrameTimer);
   await actionQueue.catch(()=>{});await finishRecorder(false);
   if(request!==restartRequest||id!==generation||model!==current)return;
   resetInProgress=true;resetFrameReady=false;waitingForResetChunk=false;pausedForReplay=false;liveVisible=false;restartFramePending=true;promptTransitionPending=false;promptFrameReady=false;liveTransition.cancel();stopReplay();
   lastCondition={action,context,appearance,reply};recordingContext=reply?context:'';
   try{
    await current.reset();if(request!==restartRequest||id!==generation||model!==current)return;
    await current.setImageStrength({image_strength:1});
    await conditionMascot(current,reference,action,context,appearance,reply);
    if(request!==restartRequest||id!==generation||model!==current)return;
    firstFrameTimer=setTimeout(()=>{if(model===current)void stop('Видеопоток не появился: проверь сеть и попробуй позже.')},25000);
    waitingForResetChunk=true;
    await current.start();if(request!==restartRequest||id!==generation||model!==current)return;
    tell('diagnostic','warm-reset');
    if(version===actionVersion)scheduleIdentityRefresh(current,id,REACTION_REFRESH_DELAY_MS);
   }catch(e){if(id===generation&&model===current){tell('error',e.message||'Не удалось обновить анимацию.');await stop()}}
  }else{
   await stop('',false);
   if(request!==restartRequest)return;
   restartFramePending=true;
   await window.reactMascot(data,action,context,appearance,reply);
  }
 });
 return restartQueue;
};
window.replyMascot=(data,action,context='',appearance='',reply='')=>{
 const request=restartRequest;
 return restartQueue.catch(()=>{}).then(async()=>{
  if(request!==restartRequest)return;
  if(model)return window.reactMascot(data,action,context,appearance,reply);
  if(request===0)return window.restartMascot(data,action,context,appearance,reply);
 });
};
async function runMascotReaction(data,action,context='',appearance='',reply='',prewarm=false){
 if(!prewarm){clearTimeout(prewarmTimer);prewarmTimer=null}prewarmActive=prewarm;
 if(starting){pendingAction={action,context,appearance,reply};if(model)armTimer();return}
 if(!model&&stopping){starting=true;await stopping;starting=false}
 const id=generation;try{if(!model){starting=true;tell('status','Подключаем Helios…');const token=JSON.parse(window.MascotBridge.reactorToken());if(token.error)throw new Error(token.error);if(id!==generation)return;const current=new HeliosModel();model=current;armTimer();
 let wasReady=false;current.on('statusChanged',state=>{if(model!==current||id!==generation)return;if(state==='ready')wasReady=true;else if(state==='disconnected'&&wasReady){tell('error','Соединение Helios прервалось. Пытаемся подключиться снова.');void stop()}});
 current.onChunkComplete(()=>{if(model!==current||id!==generation)return;if(resetInProgress&&waitingForResetChunk)resetFrameReady=true;else if(promptTransitionPending){promptTransitionPending=false;promptFrameReady=true}});
 current.onMainVideo((track,stream)=>{if(model!==current)return;liveTrack=track;liveVideo.srcObject=stream||new MediaStream([track]);let started=performance.now(),lastCheck=0,reported=false;function inspect(now,meta){if(model!==current||id!==generation||liveTrack!==track)return;try{if(!pausedForReplay&&(!resetInProgress||resetFrameReady)){if(liveVisible){if(displayVisible){measureFrames('live',now,meta);if(promptFrameReady){liveTransition.begin();promptFrameReady=false}liveTransition.render(liveVideo,now,draw)}}else if(now-lastCheck>250){lastCheck=now;sampleCtx.drawImage(liveVideo,0,0,96,54);if(hasVisibleDetail(sampleCtx.getImageData(0,0,96,54).data)){if(frame.style.opacity==='1')liveTransition.begin();liveTransition.render(liveVideo,now,draw);promptFrameReady=false;liveVisible=true;resetInProgress=false;waitingForResetChunk=false;startRecorder(track,id,recordingContext);stopReplay();frame.style.opacity='1';clearTimeout(firstFrameTimer);firstFrameTimer=null;tell(restartFramePending?'restart-frame':'frame');restartFramePending=false}}}}catch{}if(!liveVisible&&!reported&&now-started>8000){reported=true;tell('status','Ожидаем первый кадр Helios…')}liveVideo.requestVideoFrameCallback(inspect)}liveVideo.requestVideoFrameCallback(inspect);liveVideo.play().catch(()=>{if(model===current){tell('error','Не удалось воспроизвести поток.');void stop()}})});
 current.onCommandError(e=>{if(model===current){tell('error',e.reason||'Helios отклонил запрос.');void stop()}});await current.connect(token.jwt);if(id!==generation){await current.disconnect();return}reference=await current.uploadFile(await referenceBlob(data));if(id!==generation)return;await current.setImageStrength({image_strength:1});const firstAction=pendingAction||{action,context,appearance,reply};pendingAction=null;lastCondition=firstAction;recordingContext=firstAction.reply?firstAction.context:'';await conditionMascot(current,reference,firstAction.action,firstAction.context,firstAction.appearance,firstAction.reply);if(id!==generation)return;firstFrameTimer=setTimeout(()=>{if(model===current)void stop('Видеопоток не появился: проверь сеть и попробуй позже.')},25000);await current.start();if(id!==generation){await current.disconnect();return}scheduleIdentityRefresh(current,id,firstAction.reply?REACTION_REFRESH_DELAY_MS:IDENTITY_REFRESH_MS);while(pendingAction&&id===generation){const next=pendingAction;pendingAction=null;await applyAction(current,id,next.action,next.context,next.appearance,next.reply)}if(id===generation)starting=false
 }else{const current=model;await applyAction(current,id,action,context,appearance,reply)}}catch(e){if(id===generation){tell('error',e.message||'Не удалось подключиться к Helios.');await stop()}}
}
window.reactMascot=(...args)=>{
 const task=runMascotReaction(...args);
 if(starting&&!startupTask){startupTask=task;void task.finally(()=>{if(startupTask===task)startupTask=null})}
 return task;
};
// Leave the native portrait visible until the first live frame, avoiding an
// extra transition through a potentially mismatched cached Helios recording.
window.addEventListener('pagehide',()=>void stop('',false));tell('ready','missing');tell('status','');
