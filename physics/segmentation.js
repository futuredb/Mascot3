const sharp = require('sharp');
const { rgba, png, boundsOf } = require('./assets');
const { requireValue, validateAnalysis } = require('./contracts');

/** SAM's union mask becomes actual connected instances, never VLM rectangles. */
async function maskCandidates(bytes, alpha, width, height) {
  const {data}=await rgba(bytes,width,height), seen=new Uint8Array(width*height), result=[];
  const mask=Buffer.alloc(width*height);
  for(let i=0;i<mask.length;i++) mask[i]=Math.min(alpha[i],data[i*4]);
  for(let start=0;start<mask.length;start++) {
    if(seen[start] || mask[start]<=128) continue;
    const pixels=[start];seen[start]=1;
    for(let n=0;n<pixels.length;n++) {
      const i=pixels[n],x=i%width,y=Math.floor(i/width);
      for(const next of [x?i-1:-1,x+1<width?i+1:-1,y?i-width:-1,y+1<height?i+width:-1]) {
        if(next>=0 && !seen[next] && mask[next]>128) {seen[next]=1;pixels.push(next);}
      }
    }
    if(pixels.length<20) continue;
    const instance=Buffer.alloc(mask.length); for(const i of pixels) instance[i]=mask[i];
    result.push({mask:instance,bounds:boundsOf(instance,width,height),area:pixels.length});
  }
  return result;
}

function addCandidates(existing, additions) {
  for(const candidate of additions) {
    const duplicate=existing.some(other=>{
      let intersect=0;for(let i=0;i<candidate.mask.length;i++) if(candidate.mask[i]>128 && other.mask[i]>128) intersect++;
      return intersect/(candidate.area+other.area-intersect)>.85;
    });
    if(!duplicate) existing.push(candidate);
  }
  requireValue(existing.length<=32,'too_many_mask_candidates');
}

async function candidateSheet(original,candidates,frame) {
  const {data,info}=await rgba(original), tile=256, columns=Math.min(2,candidates.length);
  requireValue(candidates.length>0,'no_segmentation_candidates');
  const images=[];
  for(let n=0;n<candidates.length;n++) {
    const pixels=Buffer.from(data), mask=candidates[n].mask;
    for(let i=0;i<mask.length;i++) if(mask[i]>128) {
      pixels[i*4]=20;pixels[i*4+1]=230;pixels[i*4+2]=240;pixels[i*4+3]=255;
    }
    const left=Math.max(0,frame.left),top=Math.max(0,frame.top),width=Math.min(info.width,frame.left+frame.width)-left,height=Math.min(info.height,frame.top+frame.height)-top;
    const image=await sharp(await png(pixels,info.width,info.height)).extract({left,top,width,height}).flatten({background:'#f5f5f5'}).resize(tile,tile,{fit:'contain',background:'#f5f5f5'}).composite([{input:Buffer.from(`<svg width="256" height="256"><rect width="78" height="25" fill="white"/><text x="6" y="19" font-size="17" fill="black">C${n+1}</text></svg>`)}]).png().toBuffer();
    const b=candidates[n].bounds,pad=Math.max(12,Math.ceil(Math.max(b.right-b.left,b.bottom-b.top)*.35));
    const x=Math.max(0,b.left-pad),y=Math.max(0,b.top-pad);
    const closeup=await sharp(await png(pixels,info.width,info.height)).extract({left:x,top:y,width:Math.min(info.width,b.right+pad+1)-x,height:Math.min(info.height,b.bottom+pad+1)-y}).flatten({background:'#f5f5f5'}).resize(tile,tile,{fit:'contain',background:'#f5f5f5'}).composite([{input:Buffer.from(`<svg width="256" height="256"><rect width="120" height="25" fill="white"/><text x="6" y="19" font-size="17" fill="black">C${n+1} detail</text></svg>`)}]).png().toBuffer();
    images.push({input:image,left:(n%columns)*tile*2,top:Math.floor(n/columns)*tile});
    images.push({input:closeup,left:(n%columns)*tile*2+tile,top:Math.floor(n/columns)*tile});
  }
  return sharp({create:{width:columns*tile*2,height:Math.ceil(candidates.length/columns)*tile,channels:4,background:'#eeeeee'}}).composite(images).png().toBuffer();
}

function selectCandidateParts(review,candidates,width,height) {
  requireValue(review?.complete===true && review.rigid_regions_excluded===true,'segmentation_review_rejected');
  requireValue(Array.isArray(review.parts) && review.parts.length>0 && review.parts.length<=16,'invalid_bound_parts');
  const used=new Set(),masks=[];
  const parts=review.parts.map(part=>{
    requireValue(typeof part.candidate_id==='string' && /^C\d+$/.test(part.candidate_id),'invalid_candidate_id');
    const index=Number(part.candidate_id.slice(1))-1,candidate=candidates[index];
    requireValue(candidate && !used.has(index),'invalid_candidate_selection');used.add(index);
    requireValue(['top','bottom','left','right','center'].includes(part.attachment_region),'invalid_attachment_region');
    const b=candidate.bounds,mx=(b.left+b.right)/2,my=(b.top+b.bottom)/2;
    const target={top:[mx,b.top],bottom:[mx,b.bottom],left:[b.left,my],right:[b.right,my],center:[mx,my]}[part.attachment_region];
    let closest=-1,distance=Infinity;
    for(let i=0;i<candidate.mask.length;i++) if(candidate.mask[i]>128) {
      const d=(i%width-target[0])**2+(Math.floor(i/width)-target[1])**2;
      if(d<distance) {closest=i;distance=d;}
    }
    requireValue(closest>=0,'empty_selected_mask');masks.push(candidate.mask);
    return {...part,bounds:[b.left/width,b.top/height,(b.right+1)/width,(b.bottom+1)/height],attachment:[(closest%width)/width,Math.floor(closest/width)/height]};
  });
  return {analysis:validateAnalysis({parts,notes:review.notes}),masks};
}

module.exports={maskCandidates,addCandidates,candidateSheet,selectCandidateParts};
