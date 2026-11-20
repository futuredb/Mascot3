import {CHAT_GLITCH_EFFECTS,createGlitchRenderer,createPortraitTransition,nextChatGlitch} from '/chat-glitch.js';
import {createReplayTransition} from '/replay-transition.js';
const names=['01 · Разрыв строк','03 · Пиксельный распад','07 · Пакеты данных','08 · Потеря сигнала','09 · Вертикальный сдвиг','10 · Цифровое эхо'];
const heroes=[
 {id:'716cdb43-703b-4d7e-8a79-27f03d92b99c',name:'Уна',url:'/hero-una.png',palette:{warm:'#f05c78',cool:'#9655a8'}},
 {id:'9f226b89-df35-4d3a-9b36-96c28ea1bb62',name:'Аким',url:'/hero-akim.png',palette:{warm:'#e65362',cool:'#a99cff'}},
 {id:'a83e9d30-1270-40e6-902c-b44774e84c17',name:'Марс',url:'/hero-mars.png',palette:{warm:'#ffd34e',cool:'#f28f3b'}},
];
const cards=CHAT_GLITCH_EFFECTS.map((effect,index)=>{
 const card=document.createElement('button');card.className='card';card.setAttribute('aria-label',names[index]);
 const circle=document.createElement('div');circle.className='circle';
 const canvas=document.createElement('canvas');canvas.width=canvas.height=272;circle.append(canvas);
 const title=document.createElement('strong');title.textContent=names[index];
 const note=document.createElement('span');note.textContent='136 × 136 · прозрачный';card.append(circle,title,note);document.querySelector('#grid').append(card);
 const ctx=canvas.getContext('2d');card.onclick=()=>play();return {canvas,ctx,effect,transition:null};
});
let started=null,current=null,ready=false,lastCycle=0;
function play(){if(!ready||started!==null)return;for(const card of cards)card.transition.begin();started=performance.now()}
function animate(now){
 requestAnimationFrame(animate);if(!ready||document.hidden)return;
 if(started===null&&now-lastCycle>1250){lastCycle=now;play()}
 for(const card of cards)card.transition.render(current,now,source=>{card.ctx.clearRect(0,0,272,272);card.ctx.drawImage(source,0,0,272,272)});
 if(started!==null&&now-started>=600)started=null;
}
requestAnimationFrame(animate);
function frameFor(image){
 const source=document.createElement('canvas');source.width=source.height=256;source.getContext('2d').drawImage(image,0,0,256,256);
 const px=source.getContext('2d',{willReadFrequently:true}).getImageData(0,0,256,256).data;
 let minX=256,minY=256,maxX=0,maxY=0;
 for(let y=0;y<256;y++)for(let x=0;x<256;x++)if(px[(y*256+x)*4+3]>12){minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y)}
 const frame=document.createElement('canvas');frame.width=frame.height=256;const c=frame.getContext('2d');c.fillStyle='#eaebec';c.fillRect(0,0,256,256);
 const w=maxX-minX+1,h=maxY-minY+1,scale=Math.min(220/w,236/h),dw=w*scale,dh=h*scale;
 c.drawImage(source,minX,minY,w,h,128-dw/2,128-dh/2,dw,dh);return frame;
}
function selectHero(hero){
 document.querySelectorAll('#heroes button').forEach(button=>button.setAttribute('aria-pressed',String(button.dataset.hero===hero.id)));
 document.querySelector('#hero-caption').textContent=`${hero.name} · палитра глитча подобрана по его основным цветам. Переходы повторяются автоматически.`;
 const image=new Image();image.onload=()=>{
  current=frameFor(image);cards.forEach(card=>{card.transition=createPortraitTransition(card.canvas,card.ctx,{duration:600,effect:card.effect,palette:hero.palette});card.transition.render(current,performance.now(),source=>{card.ctx.clearRect(0,0,272,272);card.ctx.drawImage(source,0,0,272,272)})});
  ready=true;started=null;lastCycle=performance.now();document.querySelector('#play').disabled=document.querySelector('#pixel-check').disabled=false;
 };image.onerror=()=>{document.querySelector('#build').textContent=`Не удалось загрузить ${hero.name}.`};image.src=hero.url;
}
heroes.forEach(hero=>{const b=document.createElement('button');b.textContent=hero.name;b.dataset.hero=hero.id;b.setAttribute('aria-pressed','false');b.onclick=()=>selectHero(hero);document.querySelector('#heroes').append(b)});
selectHero(heroes[0]);
document.querySelector('#play').onclick=play;
document.querySelector('#pixel-check').onclick=async()=>{
 const list=document.querySelector('#checks');list.replaceChildren();
 const assert=(value,message)=>{if(!value)throw Error(message)};
 const check=(name,fn)=>{const line=document.createElement('p');try{fn();line.className='pass';line.textContent='✓ '+name}catch(e){line.className='fail';line.textContent='✕ '+name+': '+e.message}list.append(line)};
 const old=document.createElement('canvas'),incoming=document.createElement('canvas'),out=document.createElement('canvas');
 for(const c of [old,incoming,out])c.width=c.height=256;
 function fixture(canvas,color){const ctx=canvas.getContext('2d');ctx.fillStyle='#eaebec';ctx.fillRect(0,0,256,256);ctx.fillStyle=color;ctx.fillRect(60,40,136,176)}
 fixture(old,'#167c3b');fixture(incoming,'#e4a621');
 const ctx=out.getContext('2d',{willReadFrequently:true});
 for(const effect of CHAT_GLITCH_EFFECTS)check(names[CHAT_GLITCH_EFFECTS.indexOf(effect)]+': прозрачность и скрытый стык',()=>{
   const renderer=createGlitchRenderer();renderer.capturePrevious(old);
   renderer.render(ctx,incoming,effect,.5,256,256);
   const middle=ctx.getImageData(0,0,256,256).data;
   let transparent=0,coloured=0;
   for(let p=0;p<middle.length;p+=4){assert(middle[p+3]<220,'Непрозрачная заливка');if(middle[p+3]<128)transparent++;if(middle[p+3]>0)coloured++}
   assert(transparent>200&&coloured>50,'Слои не прозрачны или эффект пуст');
   renderer.render(ctx,old,effect,.5,256,256);const before=ctx.getImageData(0,0,256,256).data;
   renderer.render(ctx,incoming,effect,.5,256,256);const after=ctx.getImageData(0,0,256,256).data;
   assert(before.every((value,index)=>value===after[index]),'Видно смену источника на пике');
 });
 check('Чередуются только шесть выбранных вариантов',()=>{
   const cycle=Array.from({length:12},()=>nextChatGlitch());
   assert(new Set(cycle.slice(0,6)).size===6&&cycle.every((v,i)=>v===cycle[i%6]),'Неверная очередь');
 });
 check('Повтор и стык цикла не склеивают две позы',()=>{
   let seams=0;
   const canvas=document.createElement('canvas');canvas.width=canvas.height=256;const c=canvas.getContext('2d');c.drawImage(old,0,0);
   // The production replay controller begins on first frame and time wrap.
   const replay=createReplayTransition(canvas,c);const draw=source=>{c.clearRect(0,0,256,256);c.drawImage(source,0,0)};
   replay.render(incoming,1000,0,draw);replay.render(incoming,1210,.21,draw);
   assert(c.getImageData(128,128,1,1).data[3]<220,'Initial replay cut is exposed');seams++;
   replay.render(incoming,1500,4.9,draw);replay.render(old,1600,0,draw);replay.render(old,1810,.21,draw);
   assert(c.getImageData(128,128,1,1).data[3]<220,'Loop cut is exposed');seams++;
   assert(seams===2,'Both boundaries were not exercised');
 });
 check('Ожидание кадра и отмена не оставляют глитч',()=>{
   const canvas=document.createElement('canvas');canvas.width=canvas.height=256;const c=canvas.getContext('2d');c.drawImage(old,0,0);
   const transitions=[];const tr=createPortraitTransition(canvas,c,{onTransition:effect=>transitions.push(effect)});
   tr.begin();assert(!transitions.length,'Началось до декодированного кадра');
   const draw=source=>{c.clearRect(0,0,256,256);c.drawImage(source,0,0)};
   tr.render(incoming,10000,draw);assert(transitions.length===1,'Не началось на кадре');
   tr.cancel();tr.render(incoming,10100,draw);assert(c.getImageData(128,128,1,1).data[3]===255,'Отмена оставила прозрачность');
 });
};
async function state(){const result=await(await fetch('/state')).json();document.querySelector('#build').textContent=result.message;document.querySelector('#validate').disabled=result.status==='running'}
document.querySelector('#validate').onclick=async()=>{await fetch('/validate',{method:'POST'});state()};state();setInterval(state,2500);
