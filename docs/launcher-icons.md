# Launcher icons

The source artwork is [app/artwork/gt-icon.svg](../app/artwork/gt-icon.svg). Keep SVG sources outside
`res/`: Android resources use the generated VectorDrawable XML and WebP assets.

Regenerate from the repository root with Python 3, Pillow, and `rsvg-convert` (librsvg) installed:

```sh
python3 scripts/generate-launcher-icons.py
```

The generator preserves the SVG's charcoal background and red/white paths. It creates:

- Separate 108dp adaptive background and foreground vectors, with the original artwork scaled to 70dp
  within the central 72dp viewport so the complete mark fits inside the 66dp safe circle.
- Standard and round adaptive definitions in `mipmap-anydpi-v26`. Themed icons use the foreground's
  alpha silhouette, preserving the mark's cutouts without including its background.
- Standard and circular legacy WebP icons at 48, 72, 96, 144, and 192 pixels for Android 7.x.
- An opaque 512×512 [Play Store PNG](../app/artwork/ic_launcher-playstore.png), outside packaged resources.

After regeneration, run `./gradlew :app:assembleDebug --console=plain` and check standard, circular, and
themed previews for clipping.
