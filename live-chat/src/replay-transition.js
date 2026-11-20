import {createPortraitTransition, CHAT_GLITCH_DURATION} from './chat-glitch.js';

// Transition into playback and across loop seams; recordings remain untouched.
export function createReplayTransition(canvas, context, duration=CHAT_GLITCH_DURATION, palette) {
  const transition=createPortraitTransition(canvas,context,{duration,palette});
  let lastMediaTime=null;
  return { render(source,now,mediaTime,draw){
    if(lastMediaTime===null||(Number.isFinite(mediaTime)&&mediaTime+0.15<lastMediaTime))transition.begin();
    lastMediaTime=mediaTime;
    transition.render(source,now,draw);
  }};
}
