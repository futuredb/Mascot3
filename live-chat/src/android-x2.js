import {X2Model} from '@reactor-models/x2';
import {hasVisibleDetail} from './frame-visibility.js';
const video=document.querySelector('#video'),frame=document.querySelector('#frame'),ctx=frame.getContext('2d');
const sample=document.createElement('canvas');sample.width=96;sample.height=54;const sx=sample.getContext('2d',{willReadFrequently:true});
let model=null,source=null,sourceTimer=null,epoch=0,queue=Promise.resolve(),visible=false,pending=false;
const tell=(t,v='')=>window.MascotBridge?.event(t,String(v));
function resize(){frame.style.width=innerWidth+'px';frame.style.height=innerHeight+'px'}resize();addEventListener('resize',resize);
function instruction(action){
 const reaction=String(action||'').replace(/[\r\n]+/g,' ').trim().slice(0,350) || 'The character waits attentively, blinking and breathing.';
 return `Animate the single character already in the source image. Keep exactly one character, preserving identity, face design, gender presentation, colors, clothes, proportions, position, size and style. Fixed camera. The character visibly reacts to the current situation: ${reaction}. Make the reaction readable through changes in existing facial features, eye direction, a small head tilt or a natural small gesture when appropriate; then return to a calm pose. Keep the original background static and uniform light gray (#EAEBEC), edge to edge. No added scenery, objects, gradients, background shadows or flicker.`;
}
// Normalize edge-connected, near-neutral background pixels without changing
// isolated light details inside the character. The generated scene stays live.
let bgMask=null,bgQueue=null;
function stabilizeBackground(){
 const w=frame.width,h=frame.height,n=w*h;
 if(!w||!h)return;
 if(!bgMask||bgMask.length!==n){bgMask=new Uint8Array(n);bgQueue=new Int32Array(n)}else bgMask.fill(0);
 const image=ctx.getImageData(0,0,w,h),pixels=image.data;let read=0,write=0;
 function add(i){
  if(bgMask[i])return;bgMask[i]=1;
  const p=i*4,r=pixels[p],g=pixels[p+1],b=pixels[p+2];
  if(Math.max(r,g,b)-Math.min(r,g,b)>18||Math.max(Math.abs(r-234),Math.abs(g-235),Math.abs(b-236))>48)return;
  bgQueue[write++]=i;pixels[p]=234;pixels[p+1]=235;pixels[p+2]=236;
 }
 for(let x=0;x<w;x++){add(x);add((h-1)*w+x)}
 for(let y=1;y<h-1;y++){add(y*w);add(y*w+w-1)}
 while(read<write){const i=bgQueue[read++],x=i%w;if(x>0)add(i-1);if(x+1<w)add(i+1);if(i>=w)add(i-w);if(i<n-w)add(i+w)}
 ctx.putImageData(image,0,0);
}
async function close(){++epoch;let old=model;model=null;clearInterval(sourceTimer);sourceTimer=null;source?.stop();source=null;visible=false;pending=false;video.pause();video.srcObject=null;frame.style.opacity='0';ctx.clearRect(0,0,frame.width,frame.height);if(old)await old.disconnect()}
async function start(data,action){
 const id=++epoch;tell('status','Подключаем X2…');tell('diagnostic','token-start');const token=JSON.parse(window.MascotBridge.reactorToken());if(token.error)throw Error(token.error);tell('diagnostic','token-ready');
 const img=new Image();img.src=data;await img.decode();if(id!==epoch)return;
 const canvas=document.createElement('canvas');canvas.width=1024;canvas.height=576;const c=canvas.getContext('2d');c.fillStyle='#EAEBEC';c.fillRect(0,0,1024,576);const scale=Math.min(1024/img.width,460/img.height);c.drawImage(img,(1024-img.width*scale)/2,(576-img.height*scale)/2,img.width*scale,img.height*scale);
 const current=new X2Model();model=current;let ready=false;
 current.on('statusChanged',s=>{if(model!==current||id!==epoch)return;if(s==='ready')ready=true;else if(s==='disconnected'&&ready){tell('error','Соединение X2 прервалось.');void close()}});
 current.on('message',msg=>{if(msg?.type==='command_error'&&model===current){tell('error',msg.data?.reason||'X2 отклонил запрос.');void close()}});
 current.onMainVideo((track,stream)=>{tell('diagnostic','video-track');if(model!==current||id!==epoch)return;video.srcObject=stream||new MediaStream([track]);function draw(){if(model!==current||id!==epoch)return;try{if(video.videoWidth&&video.videoHeight){if(visible||(sx.drawImage(video,0,0,96,54),hasVisibleDetail(sx.getImageData(0,0,96,54).data))){if(frame.width!==video.videoWidth||frame.height!==video.videoHeight){frame.width=video.videoWidth;frame.height=video.videoHeight}ctx.drawImage(video,0,0,frame.width,frame.height);if(!window.MascotOverlayMode){try{stabilizeBackground()}catch{}}if(window.MascotOverlayMode){const pixels=ctx.getImageData(0,0,frame.width,frame.height);const a=pixels.data;for(let i=0;i<a.length;i+=4){const d=Math.max(Math.abs(a[i]-234),Math.abs(a[i+1]-235),Math.abs(a[i+2]-236));if(d<12)a[i+3]=0;else if(d<32)a[i+3]=Math.round(a[i+3]*(d-12)/20)}ctx.putImageData(pixels,0,0)}frame.style.opacity='1';if(!visible){visible=true;tell(pending?'restart-frame':'frame');pending=false;tell('status','')}else if(pending){pending=false;tell('restart-frame')}}}}catch{}video.requestVideoFrameCallback(draw)}video.requestVideoFrameCallback(draw);video.play().catch(()=>tell('error','Не удалось воспроизвести поток X2.'))});
 tell('diagnostic','connect-start');await current.connect(token.jwt);tell('diagnostic','connect-ready');if(id!==epoch){await current.disconnect();return}tell('diagnostic','source-start');source=canvas.captureStream(24).getVideoTracks()[0];source.contentHint='detail';sourceTimer=setInterval(()=>{c.fillStyle='#EAEBEC';c.fillRect(0,0,1024,576);c.drawImage(img,(1024-img.width*scale)/2,(576-img.height*scale)/2,img.width*scale,img.height*scale)},42);await current.publishSource(source);tell('diagnostic','source-ready');if(!await current.setPrompt({prompt:instruction(action)}))throw Error('X2 не принял запрос.');tell('diagnostic','prompt-ready');
}
async function setReaction(current,action){const accepted=await current.setPrompt({prompt:instruction(action)});if(!accepted)throw Error('X2 не подтвердил реакцию.');}
function enqueue(fn){queue=queue.catch(()=>{}).then(fn).catch(async e=>{tell('error',e.message||'Ошибка X2');await close()});return queue}
window.reactMascot=(data,action)=>enqueue(async()=>{if(!model)await start(data,action);else await setReaction(model,action)});
window.restartMascot=(data,action)=>enqueue(async()=>{pending=true;if(!model)await start(data,action);else await setReaction(model,action)});
window.replyMascot=(data,action)=>enqueue(async()=>{if(!model)await start(data,action);else await setReaction(model,action)});
window.stopMascot=()=>enqueue(close);window.closeMascot=()=>{++epoch;return enqueue(close)};addEventListener('pagehide',()=>{++epoch;void close()});tell('ready');tell('status','');
