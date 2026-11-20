# CarWith 网页投屏

把手机上的小米 CarWith 画面、声音和触控桥接到同一 Wi-Fi 下的车机浏览器。当前版本从 **1.0.0** 开始维护。

[下载 CarWithBrowser 1.0.0](https://github.com/wakin0627/car-mirror-standalone/releases/download/v1.0.0/CarWithBrowser-1.0.0-debug.apk) · [查看发布页](https://github.com/wakin0627/car-mirror-standalone/releases/tag/v1.0.0) · [使用与构建说明](carwith-browser/BROWSER_BRIDGE.md)

手机需安装兼容的小米 CarWith 和小米 CarLife，车机浏览器需支持 WebCodecs、AudioWorklet 及访问本地 HTTPS。连接同一 Wi-Fi 后，在 App 中选择画面尺寸与比例，连接并在车机输入配对码；网页上可以开启声音、全屏和调整画面适配。

网页显示帧率上限提供 30、60、120 帧，实际帧率受 CarWith 输出、车机屏幕及浏览器性能限制。画面、触控、声音和后台运行仍取决于手机系统与车机，1.0.0 不代表所有设备已完成实测。

当前发布包为与旧版同签名的调试包，SHA-256：`7497F0781A8832CF1427316D861E83613DA1935CC91AA98190D2AD1AD4E56793`。

源码位于 [carwith-browser](carwith-browser/)，基于 [mcarplay](https://github.com/wangxso/mcarplay)，相关许可和第三方声明见 [LICENSE](LICENSE)、[NOTICE](carwith-browser/NOTICE) 与 [THIRD_PARTY_NOTICES.md](carwith-browser/THIRD_PARTY_NOTICES.md)。
