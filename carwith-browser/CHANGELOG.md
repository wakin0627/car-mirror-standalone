# Changelog

Release notes are listed newest first. English is followed by Simplified Chinese.
Test releases are experimental and do not establish real-vehicle compatibility.

## [v1.0.0] - 2026-11-20

### English
- Establish CarWith browser mirroring as the 1.0.0 baseline. The phone streams CarWith video and audio to a paired vehicle browser and forwards touch input.
- Preserve selectable 30/60/120 FPS browser rendering limits, the latest-frame renderer, source size and aspect requests, and fullscreen / fit controls. The actual picture and frame rate depend on CarWith and the vehicle browser.
- Validation: 900 standard and 24 remote unit tests, browser-page test, Spotless, lint, and debug APK build passed. The 1.0.0 version number does not establish real-vehicle compatibility; video, touch, audio and background behavior remain subject to device testing.

### 简体中文
- 将 CarWith 网页投屏作为 1.0.0 版本起点，手机把 CarWith 画面和声音发送到配对后的车机浏览器，并接收触控。
- 保留网页 30/60/120 帧上限、最新帧绘制、画面尺寸及比例请求、全屏和适配功能。实际画面和帧率取决于 CarWith 和车机浏览器。
- 验证：900 项标准单元测试、24 项远程单元测试、网页测试、Spotless、lint 和调试 APK 构建通过。1.0.0 版本号不代表已完成所有实车验证，画面、触控、声音及后台运行仍需按设备测试。

[Compare changes](https://github.com/wakin0627/car-mirror-standalone/compare/f9c1c1d...v1.0.0)
