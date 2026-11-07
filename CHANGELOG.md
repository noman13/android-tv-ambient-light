# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [1.3.0] - 2026-11-07

First public release.

### Added
- **Shell screenshots** capture (default): one-shot 128×72 screenshots taken by a helper running as the shell user
  through the TV's own ADB. 1080p video keeps its frame rate on MediaTek/MStar TVs where screen recording
  halves it.
- Screen recording (MediaProjection) as a fallback for TVs without ADB.
- Modes: screen capture, single colour, five effects.
- Picture tuning: brightness, saturation, gamma, red/green/blue balance, black threshold, smoothing,
  black-border detection, capture depth.
- Strip setup: LEDs per side, bottom gap, direction, first-LED offset, RGB byte order, baud rate, LED update rate.
- Layout test, screen-off action, start on boot.
- Docker build (`docker/Dockerfile`, `scripts/build.sh`, `scripts/build.ps1`).

### Defaults
- Calibrated on the reference setup: capture 20 fps, smoothing 6, brightness 35%, saturation 150%, gamma 1.9,
  black threshold 5, border detection on, capture depth 10% / 8%, 56/36/56/36 LEDs clockwise, RGB, 230400 baud.
