# LyricProvider - 歌词提供器

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-brightgreen?style=flat-square&logo=android&logoColor=white" alt="Android">
  <img src="https://img.shields.io/github/v/release/proify/LyricProvider?style=flat-square&color=blue" alt="release">
  <img src="https://img.shields.io/github/downloads/proify/LyricProvider/total?style=flat-square&color=orange" alt="downloads">
  <img src="https://img.shields.io/github/license/proify/LyricProvider?style=flat-square" alt="license">
  <img src="https://img.shields.io/github/last-commit/proify/lyricon?style=flat-square" alt="Last Commit">
</p>

<p align="center">
  <strong>为 <a href="https://github.com/proify/lyricon">词幕 (Lyricon)</a> 打造的跨平台歌词数据聚合提供者</strong>
</p>

---

## 🎵 支持平台

本插件采用 LSPosed 注入方式，目前已完美适配以下主流音乐客户端：

### 核心适配列表

| 平台名称            | 标识符 (ID)            | 适配功能说明               |
|:----------------|:--------------------|:---------------------|
| **Apple Music** | `apple-music`       | 完美支持动态歌词、翻译歌词        |
| **网易云音乐**       | `163-music`         | 完美支持动态歌词、翻译歌词        |
| **QQ 音乐**       | `qq-music`          | 完美支持动态歌词、翻译歌词        |
| **LX 音乐**       | `lx-music`          | 支持翻译歌词               |
| **酷狗音乐/概念版**    | `kugou-music`       | **需在 App 内开启车载歌词模式** |
| **酷我音乐**        | `kuwo-music`        | **需在 App 内开启车载歌词模式** |
| **Spotify**     | `spotify-music`     | 目前仅支持标准歌词            |
| **Poweramp**    | `poweramp-music`    | 支持网络获取及本地内嵌歌词        |
| **Salt 音乐**     | `salt-player-music` | 基于魅族歌词接口适配           |

### 通用/特殊模块

| 模块名称       | 标识符 (ID)          | 适用场景                |
|:-----------|:------------------|:--------------------|
| **云音乐提供者** | `cloud-provider`  | 通用型，支持通过搜索匹配网络歌词    |
| **魅族歌词支持** | `meizhu-provider` | 适用于所有已适配魅族状态栏歌词的播放器 |

### 🚀 原生支持 (无需插件)

以下播放器已在内部集成此协议，可直接配合词幕使用：

* **光锥音乐**: [官方主页](https://coneplayer.trantor.ink/)

---

## 📥 快速安装

> [!IMPORTANT]
> **前提条件**：本插件属于扩展组件，必须配合 **[词幕](https://github.com/proify/lyricon)** 主程序方可运行。

1. **下载**：前往 [Releases 页面](https://github.com/proify/LyricProvider/releases) 获取最新的 APK
   安装包。
2. **激活**：安装后进入 **LSPosed 管理器**，勾选启用 **LyricProvider**。
3. **配置作用域**：在 LSPosed 中勾选你需要获取歌词的音乐 App（如 Apple Music、网易云等）。
4. **生效**：强行停止并重新打开对应的音乐 App 即可体验。

---

## 🛠️ 开发者指南

我们非常欢迎社区提交 Pull Request 来适配更多音乐 App。

* **技术文档**
  ：请阅读 [Provider 协议开发说明](https://github.com/proify/lyricon/blob/master/lyric/bridge/provider/README.md)
* **参考实现**：本项目中的 `apple-music` 模块是极佳的入门示例。

---

## 📊 数据统计

### 访问趋势

![Visitors](https://count.getloli.com/get/@proify_LyricProvider?theme=minecraft)

### Star 增长

[![Star History Chart](https://api.star-history.com/svg?repos=proify/LyricProvider&type=Date)](https://star-history.com/#proify/LyricProvider&Date)