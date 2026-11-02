// Crossfade the displayed end frame into the beginning. The recording itself is untouched.
export function createReplayTransition(canvas, context, duration=300) {
  const previous=document.createElement('canvas');let start=null,lastMediaTime=null;
  function capture(){if(!canvas.width||!canvas.height)return false;previous.width=canvas.width;previous.height=canvas.height;previous.getContext('2d').drawImage(canvas,0,0);return true}
  const initial=capture();
  return { render(source,now,mediaTime,draw){
    if(lastMediaTime!==null&&Number.isFinite(mediaTime)&&mediaTime+0.15<lastMediaTime){if(capture())start=now}
    if(lastMediaTime===null&&initial)start=now;
    lastMediaTime=mediaTime;
    draw(source);
    if(start!==null){const mix=Math.max(0,1-(now-start)/duration);if(mix>0){context.save();context.globalAlpha=mix;context.drawImage(previous,0,0,canvas.width,canvas.height);context.restore()}else start=null}
  }};
}
