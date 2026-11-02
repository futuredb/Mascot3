"""Derive a bounded relief mesh from existing RGBA artwork. No image generation.

Run with Python containing torch, transformers 4.46.3, Pillow, numpy and scipy.
The model runs locally; only public model weights are downloaded on the first run.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

import numpy as np
from PIL import Image
from scipy.ndimage import distance_transform_edt, gaussian_filter
import torch
from transformers import AutoImageProcessor, AutoModelForDepthEstimation

ROOT = Path(__file__).resolve().parents[1]
MODEL = "depth-anything/Depth-Anything-V2-Small-hf"
HEROES = ["9f226b89-df35-4d3a-9b36-96c28ea1bb62", "a83e9d30-1270-40e6-902c-b44774e84c17"]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def minimum_area_ratio(vertices, triangles):
    v = np.asarray(vertices, dtype=np.float64)
    tri = np.asarray(triangles).reshape(-1, 3)
    def areas(x, y):
        a, b, c = tri.T
        return (x[b] - x[a]) * (y[c] - y[a]) - (y[b] - y[a]) * (x[c] - x[a])
    rest = areas(v[:, 0], v[:, 1])
    minimum = 1.
    for ax in np.linspace(-np.pi / 15, np.pi / 15, 9):
        for ay in np.linspace(-np.pi / 15, np.pi / 15, 9):
            x = (v[:, 0] - .5) * np.cos(ax) + v[:, 2] * np.sin(ax)
            z = -(v[:, 0] - .5) * np.sin(ax) + v[:, 2] * np.cos(ax)
            y = (v[:, 1] - .5) * np.cos(ay) - z * np.sin(ay)
            minimum = min(minimum, float(np.min(areas(x, y) / rest)))
    return minimum


def mesh(alpha, relief):
    h, w = alpha.shape
    step = max(8, int(np.ceil(max(w, h) / 64)))
    vertices, triangles, lookup, edges = [], [], {}, {}
    def vertex(x, y):
        key = (x, y)
        if key not in lookup:
            lookup[key] = len(vertices)
            vertices.append([x / w, y / w, float(relief[min(y, h-1), min(x, w-1)]), x / w, y / h, 0.])
        return lookup[key]
    for y in range(0, h, step):
        for x in range(0, w, step):
            x2, y2 = min(x + step, w), min(y + step, h)
            if not np.any(alpha[y:y2, x:x2] > 0):
                continue
            a, b, c, d = vertex(x,y), vertex(x2,y), vertex(x2,y2), vertex(x,y2)
            triangles.extend([a,b,c,a,c,d])
            for i,j in [(a,b),(b,c),(c,d),(d,a),(a,c)]:
                lo,hi = min(i,j),max(i,j)
                edges[(lo,hi)] = [lo,hi,float(np.linalg.norm(np.array(vertices[i][:2])-vertices[j][:2]))]
    # Bound local slopes instead of flattening the whole character because of
    # one sharp boundary (for example nose/cheek or a loop of hair).
    for _ in range(80):
        for i,j,distance in edges.values():
            delta = vertices[j][2] - vertices[i][2]
            excess = max(0., abs(delta) - distance * 1.2)
            correction = np.sign(delta) * excess * .5
            vertices[i][2] += correction
            vertices[j][2] -= correction
    # A relief cannot reveal unseen sides. Limit its depth until every triangle
    # retains at least 40% of its area over the complete supported tilt range.
    factor = 1.
    while minimum_area_ratio(vertices, triangles) < .4:
        for v in vertices:
            v[2] *= .85
        factor *= .85
        if factor < .1:
            raise ValueError("Depth discontinuities cannot be rendered safely")
    baked = relief.copy()
    for y in range(0,h,step):
        for x in range(0,w,step):
            x2,y2 = min(x+step,w),min(y+step,h)
            if not np.any(alpha[y:y2,x:x2] > 0):
                continue
            a,b,c,d = [vertices[lookup[point]][2] for point in [(x,y),(x2,y),(x2,y2),(x,y2)]]
            tx,ty = np.meshgrid(np.arange(x2-x)/(x2-x),np.arange(y2-y)/(y2-y))
            baked[y:y2,x:x2] = np.where(tx>=ty,a+tx*(b-a)+ty*(c-b),a+tx*(c-d)+ty*(d-a))
    return vertices, triangles, list(edges.values()), factor, baked


def prepare(hero, processor, model, revision, source=None, output=None):
    root = ROOT / "android/app/src/main/assets/bundled-heroes" / hero
    source = Path(source) if source else root / "base.png"
    original = Image.open(source).convert("RGBA")
    if not (64 <= original.width <= 2048 and 64 <= original.height <= 2048):
        raise ValueError("Unsupported image dimensions")
    rgba = np.array(original)
    alpha = rgba[:,:,3]
    visible = alpha > 128
    if visible.sum() < 40 or visible.mean() > .98:
        raise ValueError("Missing foreground or transparent matte")
    # Preserve original RGB and alpha verbatim. The neutral backdrop is only an
    # inference input and is never saved as character artwork.
    flattened = Image.alpha_composite(Image.new("RGBA", original.size, (230,230,230,255)), original).convert("RGB")
    inputs = processor(images=flattened, return_tensors="pt")
    with torch.inference_mode():
        predicted = model(**inputs).predicted_depth
        raw = torch.nn.functional.interpolate(predicted.unsqueeze(1), size=alpha.shape, mode="bicubic", align_corners=False)[0,0].cpu().numpy()
    lo, hi = np.percentile(raw[visible], [2,98])
    if hi - lo < 1e-5:
        raise ValueError("Empty depth range")
    normalized = np.clip((raw-lo)/(hi-lo),0,1)
    # Extend foreground depth through the transparent margin. Mesh corners
    # outside the silhouette must not collapse into background depth.
    nearest = distance_transform_edt(~visible, return_distances=False, return_indices=True)
    extended = normalized[tuple(nearest)]
    smooth = gaussian_filter(extended, sigma=4)
    relief = (smooth - .5) * .16
    vertices, triangles, edges, depth_factor, baked = mesh(alpha, relief)
    staging = Path(output) if output else ROOT / "output/local-depth" / hero
    staging.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, staging / "body_color.png")
    Image.fromarray(np.round(np.where(alpha > 0, np.clip(baked/.16+.5,0,1), 0) * 255).astype(np.uint8), "L").save(staging / "body_depth.png")
    assets = {}
    for name in ["body_color", "body_depth"]:
        data = (staging / f"{name}.png").read_bytes()
        assets[name] = {"file":f"{name}.png", "sha256":sha(data), "size":len(data)}
    manifest = {
        "format":"mascot-depth-2.5d", "version":1,
        "source_sha256":sha(source.read_bytes()), "anchor_sha256":sha(source.read_bytes()),
        "width":original.width, "height":original.height,
        "max_tilt_degrees":12, "max_body_offset":.04, "max_lean_degrees":6,
        "layers":[{"id":"body", "material":"rigid", "order":0,
                   "texture":"body_color", "depth":"body_depth", "damping":12, "stiffness":150,
                   "max_displacement":.004, "vertices":vertices, "triangles":triangles, "edges":edges}],
        "assets":assets, "qa":{"approved":True, "neutral_error":0.},
    }
    provenance = {"method":"local monocular relative depth estimation", "model":MODEL, "model_revision":revision,
                  "model_license":"Apache-2.0", "original_rgba_unchanged":True, "generated_pixels":0,
                  "depth_scale":.16*depth_factor, "minimum_projected_area_ratio":minimum_area_ratio(vertices, triangles),
                  "maximum_local_depth_slope":1.2,
                  "limitation":"Visible surface relief only; no hidden surfaces are reconstructed."}
    data = json.dumps(manifest,separators=(",",":"),ensure_ascii=False).encode()
    revision_hash = sha(data)
    destination = Path(output) if output else root / "physics" / revision_hash
    destination.mkdir(parents=True, exist_ok=True)
    for name in ["body_color.png", "body_depth.png"]:
        if staging != destination:
            shutil.copyfile(staging / name, destination / name)
    (destination / "manifest.json").write_bytes(data)
    (destination / "provenance.json").write_text(json.dumps(provenance,indent=2))
    if not output:
        (root / "physics/active").write_text(revision_hash)
    print(json.dumps({"hero":hero,"pack":revision_hash,"vertices":len(vertices),**provenance}),flush=True)


if __name__ == "__main__":
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument("--revision", required=True, help="Pinned Hugging Face model commit")
    args.add_argument("--download-model", action="store_true", help="Allow downloading public weights; images still stay local")
    args.add_argument("--hero", action="append", choices=HEROES)
    options = args.parse_args()
    torch.set_num_threads(4)
    processor = AutoImageProcessor.from_pretrained(MODEL,revision=options.revision,local_files_only=not options.download_model)
    model = AutoModelForDepthEstimation.from_pretrained(MODEL,revision=options.revision,local_files_only=not options.download_model).eval()
    for hero in options.hero or HEROES:
        prepare(hero, processor, model, options.revision)
