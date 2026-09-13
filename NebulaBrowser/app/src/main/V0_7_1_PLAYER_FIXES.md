# NebulaBrowser V0.7.1 播放器修复

- 修复/增强 Media3 外部字幕：支持本地 SRT/VTT 和字幕 URL，并在加载后自动启用文本轨道。
- 播放界面增加独立“▶ 播放”浮动按钮，不依赖底部控制栏；暂停、播放结束或控制栏隐藏时均可一键继续/重新播放。
- 网页播放页增加独立播放入口：检测当前 WebView 正在播放的 `<video>` 的 `currentSrc`，点击后直接打开 Media3 独立播放器。
- 独立播放器继承当前网页 Referer/Cookie，提升需要登录态或防盗链视频的可播放性。
- 保持 compileSdk 34 / AGP 8.5.2 兼容，Media3 使用 1.4.1。
- blob: 视频没有可直接交给 ExoPlayer 的 HTTP 直链时，提示使用资源嗅探，而不是伪装成可播放直链。
