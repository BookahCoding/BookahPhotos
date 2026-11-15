# Synthetic playback fixture

`viewer-test.mp4` is a generated 30-second, silent 320x180 H.264 test pattern. It contains no photos, people, private metadata, or network content.

Recreate from the repository root with FFmpeg:

```powershell
ffmpeg -f lavfi -i "testsrc2=size=320x180:rate=15" -t 30 -c:v libx264 -pix_fmt yuv420p -an android/feature-viewer/src/androidTest/assets/viewer-test.mp4
```

The viewer tests copy this asset into the test application's cache and verify the player is playing before testing controls.
