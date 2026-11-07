# Contributing

Thanks for helping. Bug reports, TV compatibility reports and pull requests are all welcome.

## Reporting a problem

Open an issue with:

- TV brand and model, Android version and firmware build (Settings → Device Preferences → About)
- LED controller (board, USB chip) and firmware (Adalight sketch, WLED, HyperSerial, ...), LED count and baud rate
- Capture method in use and what you see
- The log, if you can: `adb logcat -s TvAmbient` for about 30 seconds

Please leave out Wi-Fi names, account names, serial numbers and IP addresses.

## Reporting a TV that works (or does not)

Compatibility reports help the most. If you can, include the numbers from the
"Measuring" section of [AGENTS.md](AGENTS.md): video frame rate with capture off, with screen recording and with
shell screenshots.

## Pull requests

1. Fork, create a branch, keep one topic per PR.
2. Build with Docker: `scripts/build.sh` (or `scripts\build.ps1` on Windows).
3. Test on a real Android TV, including a reboot with *Start on boot* on.
4. For changes to the capture or LED output path, add before/after measurements to the PR description.
5. Commit titles: `area: what changed`, for example `layout: support a gap on the top edge`.

Read [AGENTS.md](AGENTS.md) first: it explains the capture design, what was tried and why some approaches
were removed (one of them crashed SurfaceFlinger on a real TV).

By contributing you agree that your contribution is released under the [MIT License](LICENSE).
