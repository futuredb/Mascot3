// Ignore uniform placeholder frames; keep the mascot visible until the stream
// contains meaningful image detail. This does not judge visual quality.
export function hasVisibleDetail(rgba) {
  const total=rgba.length/4; let different=0;
  for(let i=0;i<rgba.length;i+=4){const r=rgba[i],g=rgba[i+1],b=rgba[i+2];if(Math.max(Math.abs(r-234),Math.abs(g-235),Math.abs(b-236))>35)different++}
  return different>total*.005 && different<total*.85;
}
