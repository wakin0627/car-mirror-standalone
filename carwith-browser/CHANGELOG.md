# Changelog

Release notes are listed newest first. English is followed by Simplified Chinese.
Test releases are experimental and do not establish real-vehicle compatibility.

## [v1.0.3] - 2026-11-20

### English
- Retry keyframe requests while recovering from decoder errors or transmission queue overflow; tolerate brief decoder queue bursts before resetting.
- Validation: browser-page tests include decoder error recovery and keyframe retry. Vehicle verification remains pending.

### 简体中文
- 解码出错或发送队列溢出后，等待关键帧期间持续重试；允许解码队列短暂波动后再重置。
- 验证：网页测试覆盖解码器出错恢复及关键帧请求重试；仍需实车验证。

[Compare changes](https://github.com/wakin0627/car-mirror-standalone/compare/v1.0.2...v1.0.3)

## [v1.0.2] - 2026-11-20

### English
- Allow small display-timing fluctuations in the browser frame limiter and show received, decoded and displayed frames per second for diagnosis.
- Validation: browser-page tests passed, including a 60 Hz jitter simulation. Vehicle verification remains pending.

### 简体中文
- 网页帧率限制容忍小幅刷新时间波动，并显示接收、解码和显示帧率，便于定位卡顿。
- 验证：网页测试通过，包含 60 Hz 刷新时间波动模拟；仍需实车验证。

[Compare changes](https://github.com/wakin0627/car-mirror-standalone/compare/v1.0.1...v1.0.2)

## [v1.0.1] - 2026-11-20

### English
- Add an in-app update entry for the CarWith browser bridge, pointing to this repository. It checks versions and lets users download and install an update through Android's installer.
- Restrict update assets to this repository and the CarWithBrowser package naming scheme. Verify SHA-256, package identity, version and signing certificate before installation. User approval is still required in Android.
- Validation: 901 standard unit tests, 24 remote unit tests, browser-page tests, Spotless, lint, and debug assembly passed. Device-specific installation has not yet been verified.

### 简体中文
- 为 CarWith 网页桥接增加软件内检查更新入口，连接本仓库查询版本，可下载更新并调用安卓安装程序。
- 限定更新地址和安装包名称，安装前校验 SHA-256、包名、版本与签名；安卓安装仍须用户确认。
- 验证：901 项标准单元测试、24 项远程单元测试、网页测试、Spotless、lint 和调试包构建通过；实际设备上的更新安装尚未验证。

[Compare changes](https://github.com/wakin0627/car-mirror-standalone/compare/v1.0.0...v1.0.1)

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
