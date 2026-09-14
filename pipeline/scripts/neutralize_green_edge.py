"""Replace green-dominant RGB spill at the foreground edge with white.

The alpha channel is intentionally unchanged: this is a reversible colour
neutralization probe, not a silhouette/matte modification.
"""

import argparse
import json
from collections import Counter
from pathlib import Path

import cv2
import numpy as np
from PIL import Image


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, help="Directory containing RGBA PNG frames")
    parser.add_argument("--output", required=True, help="Empty/new output directory")
    parser.add_argument("--edge-width", type=int, default=5)
    parser.add_argument("--green-excess", type=int, default=12)
    parser.add_argument("--all-foreground", action="store_true", help="Recolor green pixels throughout the visible foreground, not only at its edge")
    parser.add_argument("--hue-min", type=int, help="Optional OpenCV HSV hue lower bound (0-179)")
    parser.add_argument("--hue-max", type=int, help="Optional OpenCV HSV hue upper bound (0-179)")
    parser.add_argument("--min-saturation", type=int, default=80, help="Minimum HSV saturation when using a hue range")
    parser.add_argument("--replace-with-neighbor", action="store_true", help="Copy RGB from the nearest unmasked visible pixel instead of white")
    args = parser.parse_args()

    source = Path(args.input)
    destination = Path(args.output)
    frames = sorted(source.glob("*.png"))
    if not frames:
        raise ValueError(f"No PNG frames in {source}")
    destination.mkdir(parents=True, exist_ok=True)

    changed_pixels = 0
    edge_colours = Counter()
    green_edge_colours = Counter()
    edge_hues = Counter()
    for frame in frames:
        rgba = np.asarray(Image.open(frame).convert("RGBA")).copy()
        rgb = rgba[:, :, :3]
        alpha = rgba[:, :, 3]
        foreground = (alpha > 8).astype(np.uint8)
        kernel = np.ones((args.edge_width * 2 + 1, args.edge_width * 2 + 1), dtype=np.uint8)
        interior = cv2.erode(foreground, kernel, iterations=1)
        edge = (foreground == 1) & (interior == 0)
        for red, green, blue, opacity in rgba[edge]:
            edge_colours[(int(red) // 8 * 8, int(green) // 8 * 8, int(blue) // 8 * 8, int(opacity) // 8 * 8)] += 1

        red = rgb[:, :, 0].astype(np.int16)
        green = rgb[:, :, 1].astype(np.int16)
        blue = rgb[:, :, 2].astype(np.int16)
        green_spill = (green >= 60) & (green >= red + args.green_excess) & (green >= blue + args.green_excess)
        hsv = cv2.cvtColor(rgb, cv2.COLOR_RGB2HSV)
        hue, saturation, value = hsv[:, :, 0], hsv[:, :, 1], hsv[:, :, 2]
        if args.hue_min is not None or args.hue_max is not None:
            if args.hue_min is None or args.hue_max is None:
                raise ValueError("--hue-min and --hue-max must be used together")
            green_spill = (hue >= args.hue_min) & (hue <= args.hue_max) & (saturation >= args.min_saturation)
        for hue_value in hue[edge & (saturation >= 50) & (value >= 50)]:
            edge_hues[int(hue_value)] += 1
        candidate_spill = edge & (green >= 60) & (green >= red + 8) & (green >= blue + 8)
        for red, green, blue, opacity in rgba[candidate_spill]:
            green_edge_colours[(int(red) // 8 * 8, int(green) // 8 * 8, int(blue) // 8 * 8, int(opacity) // 8 * 8)] += 1
        mask = (foreground == 1) & green_spill if args.all_foreground else edge & green_spill
        if args.replace_with_neighbor and np.any(mask):
            source_mask = ((foreground == 1) & ~mask).astype(np.uint8)
            _, labels = cv2.distanceTransformWithLabels(
                1 - source_mask,
                cv2.DIST_L2,
                5,
                labelType=cv2.DIST_LABEL_PIXEL,
            )
            source_labels = labels[source_mask == 1]
            source_colours = np.zeros((int(labels.max()) + 1, 3), dtype=np.uint8)
            source_colours[source_labels] = rgb[source_mask == 1]
            rgb[mask] = source_colours[labels[mask]]
        else:
            rgb[mask] = (255, 255, 255)
        changed_pixels += int(mask.sum())
        Image.fromarray(rgba, "RGBA").save(destination / frame.name)

    (destination / "green_to_white_report.json").write_text(json.dumps({
        "source": str(source),
        "frame_count": len(frames),
        "edge_width_pixels": args.edge_width,
        "green_excess": args.green_excess,
        "all_foreground": args.all_foreground,
        "hue_range": [args.hue_min, args.hue_max] if args.hue_min is not None else None,
        "min_saturation": args.min_saturation,
        "pixels_recolored": changed_pixels,
        "replacement": "nearest_visible_neighbor" if args.replace_with_neighbor else "white",
        "alpha_modified": False,
        "most_common_edge_rgba_clusters": [
            {"rgba": list(colour), "pixels": count}
            for colour, count in edge_colours.most_common(30)
        ],
        "most_common_green_edge_rgba_clusters": [
            {"rgba": list(colour), "pixels": count}
            for colour, count in green_edge_colours.most_common(30)
        ],
        "most_common_saturated_edge_hues": [
            {"opencv_hue": hue, "pixels": count}
            for hue, count in edge_hues.most_common(30)
        ],
    }, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
