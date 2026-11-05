"""Prepare frame-aligned surface normals from existing video, entirely offline.

No generated RGB, inpainting, provider requests or source-video writes. A pinned,
locally cached Depth Anything V2 model estimates depth; code derives normals.
"""
import argparse, hashlib, json, math, subprocess, time
from pathlib import Path
import numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter, distance_transform_edt
import torch
import cv2
from transformers import AutoImageProcessor, AutoModelForDepthEstimation

ROOT = Path(__file__).resolve().parents[1]
MODEL = 'depth-anything/Depth-Anything-V2-Small-hf'
REVISION = '5426e4f0f36572d16453bbda7a8389317b1bef99'
TILE, COLUMNS, PAGE_FRAMES = 128, 8, 64
RECIPE = 'video-relight-v2-depth518-normal128-flow3-sigma3.8-slope1.2'
OUT = ROOT/'android/app/src/main/assets/video-lighting'
WORK = ROOT/'output/video-lighting'


def digest(file):
    return hashlib.sha256(file.read_bytes()).hexdigest()


def metadata(video):
    return json.loads(subprocess.check_output(['ffprobe','-v','error','-select_streams','v:0','-show_entries','stream=width,height,avg_frame_rate,duration:frame=best_effort_timestamp_time','-show_frames','-of','json',str(video)]))


def stable_surfaces(video, raw, masks, width, height):
    """Align neighbouring depth estimates to the current pose before blending.

    Forward/backward flow, appearance, depth and matte agreement reject newly
    exposed surfaces. Neither RGB frames nor the authored timing are changed.
    """
    gray_bytes=subprocess.check_output(['ffmpeg','-v','error','-threads','2','-i',str(video),
        '-vf',f'scale={TILE}:{TILE}','-vsync','0','-f','rawvideo','-pix_fmt','gray','pipe:1'])
    gray=np.frombuffer(gray_bytes,dtype=np.uint8).reshape((-1,TILE,TILE))
    if len(gray)!=len(raw): raise ValueError('Depth/frame count mismatch')
    bounds=gaussian_filter(np.array([np.percentile(d[m],[2,98]) for d,m in zip(raw,masks)]),sigma=(3,0),mode='nearest')
    spatial=np.empty_like(raw)
    for i,(lo,hi) in enumerate(bounds):
        depth=np.clip((raw[i]-lo)/max(hi-lo,1e-5),0,1)
        nearest=distance_transform_edt(~masks[i],return_distances=False,return_indices=True)
        spatial[i]=gaussian_filter(depth[tuple(nearest)],3.8)
    forward=[];backward=[]
    for i in range(len(gray)-1):
        forward.append(cv2.calcOpticalFlowFarneback(gray[i],gray[i+1],None,.5,3,19,3,5,1.2,0))
        backward.append(cv2.calcOpticalFlowFarneback(gray[i+1],gray[i],None,.5,3,19,3,5,1.2,0))
    yy,xx=np.mgrid[:TILE,:TILE].astype(np.float32)
    stable=np.empty_like(spatial);accepted=[]
    def warp(values,flow):
        return cv2.remap(values,xx+flow[:,:,0],yy+flow[:,:,1],cv2.INTER_LINEAR,borderMode=cv2.BORDER_REPLICATE)
    for i in range(len(gray)):
        total=spatial[i].copy();weights=np.ones_like(total)
        neighbours=[]
        if i>0: neighbours.append((i-1,backward[i-1],forward[i-1]))
        if i+1<len(gray): neighbours.append((i+1,forward[i],backward[i]))
        for j,flow,reverse in neighbours:
            depth=warp(spatial[j],flow)
            consistency=np.linalg.norm(flow+warp(reverse,flow),axis=-1)
            appearance=(gray[i].astype(np.float32)-warp(gray[j].astype(np.float32),flow))/18.
            agreement=(spatial[i]-depth)/.10
            inside=(xx+flow[:,:,0]>=0)&(xx+flow[:,:,0]<TILE-1)&(yy+flow[:,:,1]>=0)&(yy+flow[:,:,1]<TILE-1)
            valid=masks[i] & (warp(masks[j].astype(np.float32),flow)>.98) & inside
            confidence=np.exp(-consistency**2-appearance**2-agreement**2)*valid
            weight=.65*confidence
            total+=depth*weight;weights+=weight
            accepted.append(float(confidence[masks[i]].mean()))
        stable[i]=gaussian_filter(total/weights,.7)
    return stable,{'flow_confidence_mean':round(float(np.mean(accepted)),5),'temporal_radius_frames':1,
        'spatial_sigma':3.8,'depth_frames_reused':len(raw)}


def prepare(video, hero, processor, model, device, reuse_depth=False, output=None, work_root=None):
    started=time.monotonic();source_hash=digest(video);dest=Path(output) if output else OUT/source_hash
    if (dest/'manifest.json').exists():
        manifest=json.loads((dest/'manifest.json').read_text())
        if manifest.get('recipe')==RECIPE and manifest.get('source_sha256')==source_hash and len(manifest['pages'])==len(manifest['page_sha256']) and all((dest/name).exists() and digest(dest/name)==sha for name,sha in zip(manifest['pages'],manifest['page_sha256'])):
            return {'hero':hero,'action':video.stem,'sha256':source_hash,'frames':len(manifest['timestamps_us'])}
    meta=metadata(video);stream=meta['streams'][0]
    timestamps=[round(float(f['best_effort_timestamp_time'])*1e6) for f in meta['frames'] if 'best_effort_timestamp_time' in f]
    if not timestamps or timestamps[0]!=0 or any(b<=a for a,b in zip(timestamps,timestamps[1:])):
        raise ValueError(f'Unsupported timestamps: {video.name}')
    width,height=stream['width'],stream['height']
    if not (64<=width<=4096 and 64<=height<=4096) or len(timestamps)>256:
        raise ValueError('Unsupported dimensions or more than 256 source frames')
    # All bundled clips are square. Retain the source aspect for future clips.
    short=518;w=round(width/max(width,height)*short);h=round(height/max(width,height)*short)
    dest.mkdir(parents=True,exist_ok=True);work=(Path(work_root) if work_root else WORK)/source_hash;work.mkdir(parents=True,exist_ok=True)
    rawfile=work/'depth.npy';maskfile=work/'mask.npy'
    if reuse_depth:
        raw=np.load(rawfile,mmap_mode='r');masks=np.load(maskfile,mmap_mode='r')
        if raw.shape!=(len(timestamps),TILE,TILE) or masks.shape!=raw.shape or not np.isfinite(raw).all() or min(m.sum() for m in masks)<40:
            raise ValueError('Cached depth/matte shape does not match the source')
    else:
        raw=np.lib.format.open_memmap(rawfile,mode='w+',dtype='float32',shape=(len(timestamps),TILE,TILE))
        masks=np.lib.format.open_memmap(maskfile,mode='w+',dtype='bool',shape=raw.shape)
        estimate_depth(video,processor,model,device,raw,masks,w,h)
    smooth,stabilization=stable_surfaces(video,raw,masks,width,height)
    pages=[];diagnostics=[]
    for start in range(0,len(timestamps),PAGE_FRAMES):
        count=min(PAGE_FRAMES,len(timestamps)-start);rows=math.ceil(count/COLUMNS)
        atlas=np.empty((rows*TILE,COLUMNS*TILE,3),dtype='uint8');atlas[:]=[128,128,255]
        for offset in range(count):
            i=start+offset;mask=masks[i]
            gy,gx=np.gradient(smooth[i]*.32,TILE**-1,TILE**-1)
            gy*=width/height
            length=np.maximum(np.sqrt(gx*gx+gy*gy),1e-6);scale=np.minimum(1,1.2/length)
            normals=np.stack([-gx*scale,-gy*scale,np.ones_like(gx)],axis=-1)
            normals/=np.linalg.norm(normals,axis=-1,keepdims=True)
            normals[~mask]=[0,0,1]
            encoded=np.rint((normals*.5+.5)*255).astype('uint8')
            x=offset%COLUMNS*TILE;y=offset//COLUMNS*TILE;atlas[y:y+TILE,x:x+TILE]=encoded
            diagnostics.append(float(np.mean(np.linalg.norm(normals[mask,:2],axis=-1))))
        filename=f'normals-{start//PAGE_FRAMES:03d}.png';Image.fromarray(atlas).save(dest/filename);pages.append(filename)
    manifest={'format':'mascot-video-lighting','version':1,'recipe':RECIPE,'source_sha256':source_hash,'source_bytes':video.stat().st_size,'width':width,'height':height,'tile_size':TILE,'columns':COLUMNS,'page_frames':PAGE_FRAMES,'timestamps_us':timestamps,'pages':pages,'page_sha256':[digest(dest/p) for p in pages],'model':MODEL,'model_revision':REVISION,'generated_rgb_frames':0,'normal_strength_mean':round(float(np.mean(diagnostics)),5),'stabilization':stabilization}
    (dest/'manifest.json').write_text(json.dumps(manifest,separators=(',',':')))
    print(json.dumps({'complete':video.stem,'hero':hero,'frames':len(timestamps),'seconds':round(time.monotonic()-started,1),'bytes':sum(p.stat().st_size for p in dest.iterdir())}),flush=True)
    return {'hero':hero,'action':video.stem,'sha256':source_hash,'frames':len(timestamps)}


def estimate_depth(video,processor,model,device,raw,masks,w,h):
    started=time.monotonic()
    process=subprocess.Popen(['ffmpeg','-v','error','-threads','2','-i',str(video),'-vf',f'scale={w}:{h}','-vsync','0','-f','rawvideo','-pix_fmt','rgb24','pipe:1'],stdout=subprocess.PIPE)
    for i in range(len(raw)):
        data=process.stdout.read(w*h*3)
        if len(data)!=w*h*3: raise ValueError(f'Missing decoded frame {i}')
        frame=Image.frombytes('RGB',(w,h),data);rgb=np.asarray(frame.resize((TILE,TILE),Image.Resampling.BILINEAR))/255.
        corners=np.concatenate([rgb[:4].reshape(-1,3),rgb[-4:].reshape(-1,3),rgb[:,:4].reshape(-1,3),rgb[:,-4:].reshape(-1,3)])
        key=np.median(corners,axis=0)
        if key[1]>=.35 and key[1]>=key[0]+.17 and key[1]>=key[2]+.13:
            mask=rgb[:,:,1]-np.maximum(rgb[:,:,0],rgb[:,:,2])<.1
        else: mask=np.linalg.norm(rgb-key,axis=2)>.20
        if mask.sum()<40: raise ValueError(f'Empty foreground {video.name}:{i}')
        inputs=processor(images=frame,return_tensors='pt').to(device)
        with torch.inference_mode():
            depth=model(**inputs).predicted_depth
            depth=torch.nn.functional.interpolate(depth.unsqueeze(1),size=(TILE,TILE),mode='bicubic',align_corners=False)[0,0].float().cpu().numpy()
        raw[i]=depth;masks[i]=mask
        if i%24==0: print(json.dumps({'action':video.stem,'frame':i+1,'of':len(raw),'seconds':round(time.monotonic()-started,1)}),flush=True)
    process.stdout.close()
    if process.wait()!=0: raise RuntimeError('Video decoding failed')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--hero');parser.add_argument('--action');parser.add_argument('--reuse-depth',action='store_true');args=parser.parse_args()
    torch.set_num_threads(4);device='mps' if torch.backends.mps.is_available() else 'cpu'
    processor=None if args.reuse_depth else AutoImageProcessor.from_pretrained(MODEL,revision=REVISION,local_files_only=True)
    model=None if args.reuse_depth else AutoModelForDepthEstimation.from_pretrained(MODEL,revision=REVISION,local_files_only=True).eval().to(device)
    videos=sorted((ROOT/'android/app/src/main/assets/bundled-heroes').glob('*/videos/*.mp4'))
    index=[]
    for video in videos:
        hero=video.parent.parent.name
        if args.hero and hero!=args.hero: continue
        if args.action and video.stem!=args.action: continue
        index.append(prepare(video,hero,processor,model,device,args.reuse_depth))
    if not args.hero and not args.action:
        (OUT/'index.json').write_text(json.dumps(index,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'prepared':len(index),'device':device}),flush=True)
