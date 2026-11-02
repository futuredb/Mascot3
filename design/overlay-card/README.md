# Overlay card

Source: https://www.figma.com/design/YLPg8d5SNjuqDEhUAZ0rUD/Untitled?node-id=7-47
Expanded node 7:11; compact node 7:25. Retrieved 2026-10-21.

SVGs are original Figma exports. Bundled overlay_*.png resources are rendered at
4× density with the existing sharp dependency. The collapse asset is rotated
90° at runtime, as specified by Figma. No temporary asset URL is used at runtime.
SB Sans Display Medium is the existing bundled font; the action labels use it
as well (SB Sans Text is not bundled). Expanded layout uses 1.25× Figma dimensions
for the existing 248 dp Android overlay window. Compact touch window remains
76×48 dp, with 4 dp vertical padding around the 76×40 design.

The card overlaps the lower 22% of the neutral visible silhouette and is clamped
to the display. Its white tail tracks the visible centre, including device tilt.
Buttons operate locally: Yes acknowledges the example, Later collapses,
Chat opens next-hint/hide actions. No financial or generation request is sent.
