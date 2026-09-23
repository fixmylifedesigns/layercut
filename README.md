# LayerCut

A CapCut / Instagram-style video editor for Android focused on **layering**.

## Features (v0.1)
- **Main track**: add videos and images in sequence; reorder, split, duplicate, delete.
- **Overlays**: put any video or image on top of your main video (picture-in-picture). Each overlay lives on its own layer row in the timeline, can start at any time, and can be moved / resized directly on the preview (drag, pinch, or the corner handle, with center snapping). Layer up / down to change stacking.
- **Per-clip volume** (0–200%) and mute for every video, main or overlay. Images have no audio.
- **Per-clip crop** with Free / 1:1 / 9:16 / 16:9 / 4:5 / canvas presets.
- **Trim** by dragging the white handles on the selected clip in the timeline, or with the Trim dialog. Images get a Duration control.
- **Fit / Fill** for main clips.
- **Canvas**: 9:16, 16:9, 1:1, 4:5, 3:4.
- **Export**: 1080p or 4K (H.264 + AAC, 30 fps), saved to `Movies/LayerCut` with a Share button.
- Undo / redo, autosave.

## Timeline gestures
- Drag to scrub (playhead stays in the middle), pinch to zoom.
- Tap a clip to select it. Drag the white edge handles to trim.
- Long-press an overlay and drag to slide it in time.

## Build
Every push to `main` builds a signed debug APK on GitHub Actions and attaches `LayerCut.apk` to a new Release. Builds are signed with the same key so updates install over each other.

Built on AndroidX Media3 Transformer (`CompositionPlayer` for preview, `Transformer` for export).
