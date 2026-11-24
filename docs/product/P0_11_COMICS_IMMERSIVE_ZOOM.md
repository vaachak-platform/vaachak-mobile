# P0-11 — Immersive Comics Reader and Touch Zoom

## UI scope

This replaces only the Comics reader display, not the Books shelf, reader, catalogs, or sync engine.
The Comics shelf, CBZ/CBR decoders, duplicate checks, and persisted reading positions are unchanged.

## Visual behavior

- Reader opens as its own navigation destination, with the Books/Comics/Audio/Settings tabs absent.
- Phone status and navigation bars are temporarily hidden on entry. The standard Android back gesture still returns to the Comics shelf. Bars are restored when leaving the reader. On devices using 3-button navigation, users can reveal transient system bars with an edge swipe.
- Compact title/actions and page-number/previous/next controls overlay the page only when requested. **Tap the middle of a page** to show/hide; buttons are not reserved in a permanent header/footer.
- **Fit Page** (default when saved Comic preference is Fit Page): all artwork remains visible, which may result in letterboxing because comics and phones have different aspect ratios.
- **Fit Width**: image fills the width. Pages taller than the screen can be panned vertically.
- **Fill Screen**: fills the viewport and deliberately crops excess width/height; zoom or drag to inspect the edges. Never the only reading option.
- Pinch zoom: 1x–5x relative to the selected fit. Drag to pan within page bounds.
- Double tap: toggles magnification (2.5x) and base fit.
- Paged reading: tap left/right to turn when not zoomed; middle toggles controls; while zoomed, taps do not turn pages.
- Continuous vertical reading: natural list scroll when unzoomed; each page supports pinch/double-tap magnification.
- A newly selected page resets its zoom/pan, without changing persisted page number.
- No zoom animation, respecting reduced-motion and e-ink requirements.

## Samsung acceptance (do not commit until passed)

1. Import/open an existing CBZ and an existing CBR. Confirm no data migration or re-import is required.
2. Reader launches without Vaachak main tabs, status bar, or Android navigation bar.
3. Tap center to reveal controls; tap again to hide. Back always returns to Comics, and status/navigation bars return.
4. Test pinch out/in, double-tap magnify/reset, and drag-to-pan with the two supplied comics.
5. Switch among Fit Page, Fit Width, Fill Screen; only Fill Screen may intentionally crop the edges at base zoom.
6. While zoomed, tapping image edges must not skip a page. At base zoom, left/right taps must turn pages.
7. Page numbers, reading direction, progress, and resume still work; test continuous scroll zoom too.
8. Rotate phone, leave/re-enter Reader, and confirm no crash or corrupted graphics.
9. Repeat automated unit and device instrumentation checks; confirm Books remains identical.

## Known constraints

Image decode remains capped at the existing 2048px max dimension and RGB565.
Pinch zoom magnifies the decoded image; very high zoom may therefore appear pixelated.
For extreme-quality zoom on very large scanned pages, a future tiled decoder would be required.
The physical aspect-ratio difference means you cannot eliminate every letterbox region without cropping or distorting pages; Fit Page intentionally preserves the artwork.
