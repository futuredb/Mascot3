"""Server entry point: estimate existing media, never generate or modify RGB.

Weights are pinned and provisioned at deployment time. Runtime has no provider
credentials and never downloads a model or calls a paid generation service.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import torch
from transformers import AutoImageProcessor, AutoModelForDepthEstimation

MODEL = 'depth-anything/Depth-Anything-V2-Small-hf'
REVISION = '5426e4f0f36572d16453bbda7a8389317b1bef99'


def module(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + '.py'))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kind', choices=['image', 'video'], required=True)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True)
    args = parser.parse_args()
    torch.set_num_threads(2)
    device = 'mps' if torch.backends.mps.is_available() else 'cpu'
    processor = AutoImageProcessor.from_pretrained(MODEL, revision=REVISION, local_files_only=True)
    model = AutoModelForDepthEstimation.from_pretrained(MODEL, revision=REVISION, local_files_only=True).eval()
    if args.kind == 'image':
        module('prepare-local-depth').prepare('uploaded', processor, model, REVISION, args.source, args.output)
    else:
        module('prepare-video-lighting').prepare(args.source, 'uploaded', processor, model.to(device), device,
                                                output=args.output, work_root=args.work)
    print(json.dumps({'status': 'ready', 'generated_rgb_frames': 0}), flush=True)


if __name__ == '__main__':
    main()
