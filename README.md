# 车机投屏独立版（Root Android）

Android App 内置 scrcpy 3.1 服务端、HTTPS 网页、视频转发、触控反控与系统音频转发。目标是手机安装 APK 后直接操作，无需在手机安装 Termux、Node.js，也无需每次通过电脑运行 ADB 命令。

## 下载

下载 [CarMirrorStandalone-0.1-debug.apk](CarMirrorStandalone-0.1-debug.apk)，在 Android 手机上安装。此包为 **0.1 调试版原型**，尚未在真实手机与车机上完成端到端验证；请先在备用设备上测试。APK 的 SHA-256：`9FF268A62C663A5EFADBF94D12C8B9EC3C41251734D4490EC1BBD3F362649066`。

这是使用 Android 调试密钥签名的安装包。后续若改用正式签名，可能需要卸载此版后再安装；卸载会清除本 App 的数据。

## 适用范围

- Android 11 及以上、已 Root 且可给 App 授权 `su` 的手机。
- 手机与车机连接同一个 Wi-Fi，车机浏览器支持 WebCodecs、AudioWorklet 和 HTTPS。
- 默认画面为萤火虫车机的 16:10 比例。App 可选 1280×800、1920×1200、2560×1600；如果方向倒置，可勾选方向切换后重连。

## 使用

1. 在手机直接安装 APK，连接车机 Wi-Fi。
2. 打开「车机投屏」，选画质并点「连接车机」，首次授予 Root 权限。
3. 在车机浏览器打开 App 显示的地址，首次接受手机生成的本地 HTTPS 证书。
4. 网页点「开启车机声音」；浏览器的音频播放需要用户点按。
5. 使用结束后在 App、车机网页或通知栏点「断开」。App 会恢复启动前的分辨率、DPI、旋转、亮度与屏幕超时。Wi-Fi 断开时也会停止投屏。

需要让手机变暗时，使用网页「调暗手机屏幕」按钮；直接按手机电源键可能让采集画面暂停。

连接期间，scrcpy 的 `output` 音源接管手机系统输出并转发到网页；断开时释放音频捕获，由 Android 恢复原来的输出路由。若手机仍连接蓝牙音箱，Android 可能恢复到蓝牙而非机身扬声器。

## 现阶段验证

这是可编译的独立版原型。当前已完成 APK 构建和静态检查；Root 启动、Android Keystore HTTPS、车机浏览器音频和不同品牌的屏幕方向仍需在真实手机上验证后才能称为稳定版。

## 构建

需要 JDK 17、Android SDK Platform 35、Build Tools 35.0.0、Gradle 8.13。运行 `gradle assembleDebug`。APK 输出位于 `app/build/outputs/apk/debug/app-debug.apk`。

本项目内附 scrcpy v3.1 服务端，其 [Apache 2.0 许可](https://github.com/Genymobile/scrcpy/blob/v3.1/LICENSE) 见 APK assets 中的 `SCRCPY-LICENSE.txt`。
