# V0.7.4 独立视频播放器下载功能

基于 V0.7.3 Userscript 自动安装修复版继续修改。

## 新增
- 独立播放器顶部增加“下载”按钮。
- 播放器“⋮”菜单增加“下载当前视频”。
- 使用 Android DownloadManager，下载在 Activity 离开后仍可继续。
- 下载请求继承当前播放器的 Cookie、Referer、User-Agent。
- 自动根据常见视频扩展名生成文件名和 MIME 类型。
- 下载完成/失败通过系统下载通知和 Nebula Toast 提示。
- 对 `blob:` / `data:` 资源给出明确提示。
- 对 HLS `.m3u8` / DASH `.mpd` 不伪装成普通视频下载：当前版本提示其为分片流，避免用户得到不可播放的清单文件。

## 保留
- Media3 / ExoPlayer 播放
- HLS / DASH 播放
- 清晰度选择
- 左右滑动快进/快退
- 上下滑动亮度/音量
- 倍速、字幕、音轨、循环、全屏、PIP、播放位置记忆
- Userscript 自动安装修复

## 构建
- compileSdk 34
- targetSdk 34
- AGP 8.5.2
- Media3 1.4.1
