# 车机投屏独立版（Root Android）

## 小米 13 CarWith 网页桥接测试版

小米 13 / HyperOS 4 可试用 [CarWithBrowser-0.1.2-test.apk](https://github.com/wakin0627/car-mirror-standalone/releases/download/carwith-v0.1.2-test/CarWithBrowser-0.1.2-test.apk)。它通过手机本机的 CarWith/小米 CarLife 通道取得车载界面，再经同一 Wi-Fi 的 HTTPS 网页送到车机；手机屏幕可独立操作。车机浏览器打开 App 显示的网址，输入六位配对码即可连接。源码与验证边界见 [carwith-browser/BROWSER_BRIDGE.md](carwith-browser/BROWSER_BRIDGE.md)。此测试版尚未在小米 13 和真实车机上验证；它使用独立包名，可与下方原有投屏 App 共存。代码源于 [mcarplay](https://github.com/wangxso/mcarplay) 的 MIT 许可实现，保留其许可与署名。

## 原有屏幕镜像版

Android App 内置 scrcpy 3.1 服务端、HTTPS 网页、视频转发、触控反控与系统音频转发。目标是手机安装 APK 后直接操作，无需在手机安装 Termux、Node.js，也无需每次通过电脑运行 ADB 命令。

## 下载

下载 [CarMirrorStandalone-0.1.5-debug.apk](CarMirrorStandalone-0.1.5-debug.apk)，在 Android 手机上安装。此包为 **0.1.5 调试版原型**，独立屏幕模式仍需在真实车机上验证。APK 的 SHA-256：`13885D66579FA13927DF47C78E002CCEBD00BE98209E0B802413001C80625BA2`。

0.1.1 起默认不再修改手机的分辨率和 DPI，以免手机屏幕触点失准。需要车机 16:10 平板布局时，可手动勾选「强制平板比例」，但这可能再次影响手机触控。0.1.2 根据车机的 RSA 握手错误，改为在每台手机的 App 私有目录生成软件 TLS 密钥与证书，避开 Android Keystore 的 RSA 握手故障。证书为自签名，车机浏览器可能要求首次确认；是否解决这台车机的连接失败，还需在车机上验证。

0.1.3 将最高帧率设为 30，按画质档位选择码率，缩短视频发送队列，并直接绘制解码帧，以减少操作后画面落后。车机网页左下角会显示实际视频尺寸和解码帧率；右侧「铺满屏幕（裁边）」可以去掉黑边，但会裁去部分画面，点「显示完整画面」可切回。此选项不会改变手机分辨率。

0.1.4 修复手机 App 状态被单次无效 HTTPS 请求覆盖的问题。车机视频连接成功后显示「车机已连接」；连接期间，其他客户端的 TLS 报错只写入日志，不再遮盖正常连接状态。视频断开后显示「服务已启动，等待车机」。

0.1.5 默认新增车机独立屏幕：用 scrcpy 虚拟显示器生成横屏平板画面，不改手机实体屏幕的分辨率、DPI 和方向。可选 16:10、16:9、2:1 车机比例与宽 1280、1920、2560 的画质；车机网页「应用列表」可打开手机应用。若个别手机的虚拟显示器无法启动，可取消「车机独立屏幕」回到旧版镜像模式。新增 App 内「检查更新」：从本仓库 GitHub Release 下载、校验 SHA-256 与签名，再交给 Android 系统安装；首次使用需允许本 App 安装未知应用。更新时手机需要能访问 GitHub。另有 `http://手机IP:8080/` 诊断页，用于测试车机是否能连到手机，不承载投屏。

这是使用 Android 调试密钥签名的安装包。后续若改用正式签名，可能需要卸载此版后再安装；卸载会清除本 App 的数据。

## 适用范围

- Android 11 及以上、已 Root 且可给 App 授权 `su` 的手机。
- 手机与车机连接同一个 Wi-Fi，车机浏览器支持 WebCodecs、AudioWorklet 和 HTTPS。
- 独立屏幕可选 16:10、16:9、2:1；应用仍可能因自身限制只支持竖屏。旧版镜像模式仍可用，其画面比例由手机决定。

## 使用

1. 在手机直接安装 APK，连接车机 Wi-Fi。
2. 打开「车机投屏」，选画质和车机屏幕比例，点「连接车机」，首次授予 Root 权限。
3. 在车机浏览器打开 App 显示的地址，首次接受手机生成的本地 HTTPS 证书。
4. 网页点「开启车机声音」和「应用列表」。浏览器的音频播放需要用户点按。
5. 使用结束后在 App、车机网页或通知栏点「断开」。App 会恢复启动前的显示设置、亮度与屏幕超时。Wi-Fi 断开时也会停止投屏。

需要让手机变暗时，使用网页「调暗手机屏幕」按钮；直接按手机电源键可能让采集画面暂停。

连接期间，scrcpy 的 `output` 音源接管手机系统输出并转发到网页；断开时释放音频捕获，由 Android 恢复原来的输出路由。若手机仍连接蓝牙音箱，Android 可能恢复到蓝牙而非机身扬声器。

## 现阶段验证

这是可编译的独立版原型。当前已完成 APK 构建和静态检查；新虚拟显示器、App 内安装更新、车机浏览器音频和不同品牌的表现仍需在真实手机上验证后才能称为稳定版。

## 构建

需要 JDK 17、Android SDK Platform 35、Build Tools 35.0.0、Gradle 8.13。运行 `gradle assembleDebug`。APK 输出位于 `app/build/outputs/apk/debug/app-debug.apk`。

本项目内附 scrcpy v3.1 服务端，其 [Apache 2.0 许可](https://github.com/Genymobile/scrcpy/blob/v3.1/LICENSE) 见 APK assets 中的 `SCRCPY-LICENSE.txt`。
