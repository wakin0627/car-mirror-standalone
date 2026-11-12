# CarWith 网页桥接实验版

本分支从 [mcarplay](https://github.com/wangxso/mcarplay) 的 MIT 许可源码派生，保留上游 `LICENSE`、`NOTICE` 和 `THIRD_PARTY_NOTICES.md`。新增浏览器模式：小米 CarWith/小米 CarLife 在手机本机生成车载画面，本 App 接收其七路本地 CarLife 通道，把 H.264 视频、PCM 音频通过 HTTPS/WebSocket 发给同一 Wi-Fi 上的车机浏览器，并把网页单点触控送回 CarWith。没有内置或修改小米、百度 APK。

测试 APK：[CarWithBrowser-0.1.1-test.apk](https://github.com/wakin0627/carwith-browser-bridge/releases/download/v0.1.1-test/CarWithBrowser-0.1.1-test.apk)。SHA-256：`F5431A54908A74DAB7B73015438643C062FA2705433EE46609866988E40E573E`。

## 使用范围

- 目标手机：已装 CarWith 和小米 CarLife 组件的小米 13 / HyperOS 4；实机尚未验证。
- 车机浏览器需要支持 WebCodecs、AudioWorklet 和 HTTPS 自签名证书。无需车机提供原生 CarLife 入口。
- 连接前手机与车机必须在同一个 Wi-Fi；车机浏览器必须能访问手机 IP。
- 本模式与仓库原有的无线 CarPlay 模式不能同时占用一个 CarWith 会话。

## 操作

1. 手机安装 `app/build/outputs/apk/standard/debug/app-standard-debug.apk`，连接车机 Wi-Fi。
2. 打开「CarWith 网页桥接」，选择画质和比例，点「连接，显示 CarWith 画面」。若小米系统提示 CarWith 启动或后台弹出权限，请允许。
3. 车机浏览器打开 App 显示的 HTTPS 网址，输入 App 显示的六位配对码。首次接受自签名证书。
4. 网页点「开启车机声音」，用车机触屏操作 CarWith。结束后点「断开连接」。

## 验证边界

已完成离线编译、上游协议单元测试和 Android lint。尚未在小米 13 / HyperOS 4 或真实车机上验证启动 CarWith、视频尺寸、音频路由、触控及 Wi-Fi 稳定性。若 CarWith 不接受请求的 16:10 比例，实际视频尺寸会在网页左下角显示。浏览器连接网址只限可信的车机 Wi-Fi 使用；配对码和配对后的随机密钥随 App 进程重启而变化。
