(() => {
 const field=document.getElementById('field'),canvas=document.getElementById('gradientCanvas'),marker=document.getElementById('fieldMarker');
 const reduced=matchMedia('(prefers-reduced-motion: reduce)');
 // Figma-style 60% corner smoothing: tangent cubic transitions into a circular arc.
 const svgNS='http://www.w3.org/2000/svg';
 const outline=document.createElementNS(svgNS,'svg'),edge=document.createElementNS(svgNS,'path');
 outline.classList.add('field-glass-outline');outline.setAttribute('aria-hidden','true');
 edge.setAttribute('fill','none');edge.setAttribute('stroke','rgba(255,255,255,.6)');edge.setAttribute('stroke-width','2');outline.append(edge);field.append(outline);
 function glassContour(){
  const w=field.clientWidth,h=field.clientHeight,r=Math.min(32,w/3.2,h/3.2),s=.6,p=(1+s)*r;
  const theta=s*Math.PI/4,arcAngle=(1-s)*Math.PI/2;
  const c=r*Math.tan(theta/2)*Math.cos(theta),d=c*Math.tan(theta);
  const arc=r*Math.sin(arcAngle/2)*Math.SQRT2,b=(p-arc-c-d)/3,a=2*b;
  let path=`M ${w-p} 0`;
  const transform=[(x,y)=>[w-p+x,y],(x,y)=>[w-y,h-p+x],(x,y)=>[p-x,h-y],(x,y)=>[y,p-x]];
  for(let i=0;i<4;i++){
   const t=transform[i],point=(x,y)=>t(x,y).join(' ');
   path+=` C ${point(a,0)} ${point(a+b,0)} ${point(a+b+c,d)} A ${r} ${r} 0 0 1 ${point(p-d,p-a-b-c)} C ${point(p,p-a-b)} ${point(p,p-a)} ${point(p,p)}`;
   if(i<3)path+=` L ${transform[i+1](0,0).join(' ')}`;
  }
  path+=' Z';field.style.clipPath=`path("${path}")`;outline.setAttribute('viewBox',`0 0 ${w} ${h}`);edge.setAttribute('d',path);
 }
 new ResizeObserver(glassContour).observe(field);glassContour();

 let selected=-1,palette=0,frame=0,last=0,time=0;
 const current={x:.5,y:.5},target={...current},trail={x:.5,y:.5},wakePoint={x:.5,y:.5};
 const gl=canvas.getContext('webgl',{alpha:false,antialias:false});
 if(!gl){canvas.style.display='none';field.style.background='radial-gradient(at 10% 20%,#eaffb8,transparent 65%),radial-gradient(at 80% 20%,#8770ff,transparent 65%),radial-gradient(at 20% 90%,#9fe4d4,transparent 65%),#f4a4df'}
 let program,uniforms;
 if(gl){
 const vertex='attribute vec2 position; varying vec2 uv; void main(){uv=position*.5+.5;gl_Position=vec4(position,0.,1.);}';
 const fragment=`precision highp float; varying vec2 uv;
 uniform vec2 pointer; uniform vec2 trail; uniform vec2 wakePoint;
 uniform float time; uniform float palette; uniform vec3 background; uniform float aspect;
 float blob(vec2 p,vec2 c,float width){vec2 d=p-c;return exp(-dot(d,d)/width);}
 void main(){
 vec2 p=vec2(uv.x,1.-uv.y);
 vec2 velocity=pointer-trail;
 // Broad, overlapping colour clouds. No quadrant masks or seams.
 vec2 q=p;
 float influence=blob(p,trail,.30);
 q-=velocity*1.65*influence;
 q+=vec2(sin(p.y*3.8+time*.13),cos(p.x*3.4-time*.11))*.055;
 vec2 drift=(pointer-.5)*.32;
 vec2 c1=vec2(.13,.15)+drift+vec2(sin(time*.09),cos(time*.12))*.08;
 vec2 c2=vec2(.88,.24)+drift*.6;
 vec2 c3=vec2(.16,.90)+drift*.8;
 vec2 c4=vec2(.85,.86)-drift*.45;
 float a=blob(q,c1,.24),b=blob(q,c2,.28),c=blob(q,c3,.27),d=blob(q,c4,.24);
 vec3 cream=vec3(.97,.95,.76),lilac=vec3(.25,.33,.78),mint=vec3(.23,.72,.59),rose=vec3(.95,.46,.76);
 if(palette>.5){cream=vec3(.77,.91,.84);lilac=vec3(.48,.65,.84);mint=vec3(.51,.78,.79);rose=vec3(.75,.66,.88);}
 vec3 col=(cream*a+lilac*b+mint*c+rose*d)/(a+b+c+d);
 // The moving pigment follows two delayed points, leaving a soft wake.
 vec3 pigment=mix(mint,lilac,smoothstep(.05,.95,pointer.x));
 pigment=mix(pigment,rose,smoothstep(.15,.95,pointer.y)*.55);
 float ribbon=blob(q,trail,.065)*.30+blob(q,wakePoint,.105)*.22;
 col=mix(col,pigment,ribbon);
 col=mix(col,vec3(1.,.98,.94),blob(q,pointer+vec2(-.06,-.08),.035)*.22);
 // Cobalt and deep violet gather towards the proactive / solutions corner.
 float corner=smoothstep(.28,.94,pointer.x)*(1.-smoothstep(.08,.75,pointer.y));
 float region=blob(q,vec2(.80,.27),.34);
 float wave=sin(q.x*2.2+q.y*2.8-time*.12)*.5+.5;
 vec3 jewel=mix(vec3(.045,.10,.34),vec3(.12,.32,.94),smoothstep(0.,1.,wave));
 jewel=mix(jewel,vec3(.32,.10,.51),blob(q,vec2(.91,.61),.18)*.5);
 float pink=blob(q,vec2(.72+.08*sin(time*.13),.17+.05*cos(time*.11)),.14);
 jewel=mix(jewel,vec3(.98,.43,.89),pink*.32);
 col=mix(col,jewel,region*(.30+.65*corner));
 float grain=(fract(sin(dot(gl_FragCoord.xy,vec2(12.9898,78.233)))*43758.5453)-.5)/255.;
 // Saturation peaks at the pointer, then the pigment dissolves into the UI surface.
 vec2 distanceVector=(p-pointer)*vec2(aspect,1.);
 float distanceToPointer=length(distanceVector);
 float presence=1.-smoothstep(.1197,2.1204,distanceToPointer);
 presence=presence*presence;
 float luminance=dot(col,vec3(.2126,.7152,.0722));
 vec3 saturated=clamp(vec3(luminance)+(col-vec3(luminance))*1.65,0.,1.);
 saturated=mix(saturated,saturated*.87,.65);
 col=mix(background,saturated,presence);
 gl_FragColor=vec4(col+grain*presence,1.);}`;
 const compile=(type,source)=>{const shader=gl.createShader(type);gl.shaderSource(shader,source);gl.compileShader(shader);if(!gl.getShaderParameter(shader,gl.COMPILE_STATUS))throw new Error(gl.getShaderInfoLog(shader));return shader};
 program=gl.createProgram();gl.attachShader(program,compile(gl.VERTEX_SHADER,vertex));gl.attachShader(program,compile(gl.FRAGMENT_SHADER,fragment));gl.linkProgram(program);if(!gl.getProgramParameter(program,gl.LINK_STATUS))throw new Error(gl.getProgramInfoLog(program));gl.useProgram(program);
 const buffer=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,buffer);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-1,-1,1,-1,-1,1,-1,1,1,-1,1,1]),gl.STATIC_DRAW);const attr=gl.getAttribLocation(program,'position');gl.enableVertexAttribArray(attr);gl.vertexAttribPointer(attr,2,gl.FLOAT,false,0,0);uniforms={background:gl.getUniformLocation(program,'background'),aspect:gl.getUniformLocation(program,'aspect'),pointer:gl.getUniformLocation(program,'pointer'),trail:gl.getUniformLocation(program,'trail'),wakePoint:gl.getUniformLocation(program,'wakePoint'),time:gl.getUniformLocation(program,'time'),palette:gl.getUniformLocation(program,'palette')};
 }
 function draw(){if(!gl)return;const dpr=Math.min(devicePixelRatio,2),w=Math.round(field.clientWidth*dpr),h=Math.round(field.clientHeight*dpr);if(canvas.width!==w||canvas.height!==h){canvas.width=w;canvas.height=h;gl.viewport(0,0,w,h)}const darkSurface=document.body.classList.contains('dark');gl.uniform3f(uniforms.background,...(darkSurface?[34/255,37/255,33/255]:[234/255,235/255,236/255]));gl.uniform1f(uniforms.aspect,w/h);gl.uniform2f(uniforms.pointer,current.x,current.y);gl.uniform2f(uniforms.trail,trail.x,trail.y);gl.uniform2f(uniforms.wakePoint,wakePoint.x,wakePoint.y);gl.uniform1f(uniforms.time,time);gl.uniform1f(uniforms.palette,palette);gl.drawArrays(gl.TRIANGLES,0,6)}
 function tick(now){const dt=Math.min(.05,(now-last)/1000||.016);last=now;if(!reduced.matches)time+=dt;let moving=false;for(const key in current){current[key]+= (target[key]-current[key])*(reduced.matches?1:1-Math.exp(-dt*7));if(Math.abs(target[key]-current[key])>.0001)moving=true}marker.style.left=current.x*100+'%';marker.style.top=current.y*100+'%';for(const key of ['x','y']){trail[key]+=(current[key]-trail[key])*(reduced.matches?1:1-Math.exp(-dt*3.2));wakePoint[key]+=(trail[key]-wakePoint[key])*(reduced.matches?1:1-Math.exp(-dt*2));}field.dataset.pointer=`${current.x.toFixed(3)},${current.y.toFixed(3)}`;draw();frame=0;if(!document.hidden&&(!reduced.matches||moving))frame=requestAnimationFrame(tick)}
 function wake(){if(!frame){last=performance.now();frame=requestAnimationFrame(tick)}}
 function choose(x,y){x=Math.max(.06,Math.min(.94,x));y=Math.max(.06,Math.min(.94,y));target.x=x;target.y=y;if(window.MascotPersonality)window.MascotPersonality.select((x>=.5?1:0)+(y>=.5?2:0),x,y);field.parentElement.dataset.activeCorner=(y<.5?'top':'bottom')+'-'+(x<.5?'left':'right');const next=(x>=.5?1:0)+(y>=.5?2:0);if(next!==selected){selected=next;const s=flowScenarios[next];document.getElementById('characterTitle').innerText=s.title;document.getElementById('characterCopy').innerText=s.copy;document.getElementById('selectionName').textContent=s.name;document.getElementById('selectionNumber').textContent=`0${next+1} — 04`;document.getElementById('saveStatus').textContent='Можно выбрать другой вариант';document.getElementById('buy').innerHTML='Сохранить характер <span>↗</span>'}wake()}
 window.setPersonalityPoint=choose;
 function pointer(e){const rect=field.getBoundingClientRect();choose((e.clientX-rect.left)/rect.width,(e.clientY-rect.top)/rect.height)}
 field.addEventListener('pointerenter',()=>{field.parentElement.classList.add('hints-visible')});field.addEventListener('pointerleave',()=>{field.parentElement.classList.remove('hints-visible')});field.addEventListener('focus',()=>{field.parentElement.classList.add('hints-visible')});field.addEventListener('blur',()=>{field.parentElement.classList.remove('hints-visible')});field.addEventListener('pointermove',pointer);field.addEventListener('pointerdown',e=>{field.parentElement.classList.add('hints-visible');field.setPointerCapture(e.pointerId);pointer(e)});
 field.addEventListener('keydown',e=>{if(!['ArrowLeft','ArrowRight','ArrowUp','ArrowDown'].includes(e.key))return;e.preventDefault();choose(e.key==='ArrowLeft'?.24:e.key==='ArrowRight'?.76:target.x,e.key==='ArrowUp'?.24:e.key==='ArrowDown'?.76:target.y)});

 const initialIndex=Math.max(0,Math.min(3,Number(new URLSearchParams(location.search).get('selection')||1)));
 choose(initialIndex%2 ? .76 : .24,initialIndex>=2 ? .76 : .24);
 new MutationObserver(wake).observe(document.body,{attributes:true,attributeFilter:['class']});new ResizeObserver(wake).observe(field);document.addEventListener('visibilitychange',()=>{if(document.hidden){cancelAnimationFrame(frame);frame=0}else wake()});reduced.addEventListener('change',wake);wake();
})();
