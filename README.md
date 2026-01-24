<!--suppress ALL -->
<h1 align="center">LyricProvider - 歌词提供器</h1>

<p align="center">
  <img src="https://img.shields.io/github/v/release/proify/LyricProvider?style=flat-square&color=blue" alt="release">
  <img src="https://img.shields.io/github/downloads/proify/LyricProvider/total?style=flat-square&color=orange" alt="downloads">
  <img src="https://img.shields.io/github/actions/workflow/status/proify/LyricProvider/android.yml?style=flat-square" alt="Build Status">
  <img src="https://img.shields.io/github/license/proify/LyricProvider?style=flat-square" alt="license">
</p>

<p align="center">
  <b>针对 <a href="https://github.com/proify/lyricon">词幕 (lyricon)</a> 开发的标准化歌词获取插件库</b>
</p>

---

## 🎵 支持平台

目前已适配以下音乐客户端的歌词抓取：

| 平台                 | 状态      | 说明               |
|:-------------------|:--------|:-----------------|
| 🍎 **Apple Music** | 🟢 稳定   | 支持动态歌词/翻译        |
| ☁️ **网易云音乐**       | 🟢 稳定   | 支持动态歌词/翻译        |
| 🎸 **更多平台**        | 🛠️ 开发中 | 欢迎提交 PR 适配更多 App |

---

## 📥 安装指南

> [!IMPORTANT]
> 本插件必须配合 **[词幕](https://github.com/proify/lyricon)** 主程序使用。

1. **获取插件**：[前往 Releases 页面](https://github.com/proify/LyricProvider/releases) 下载对应的
   `.apk` 文件。
2. **激活模块**：安装后在 **LSPosed** 管理器中找到对应的插件并勾选**启用**。
3. **设置作用域**：确保插件的作用域已勾选对应的音乐 App（如 Apple Music 或网易云）。
4. **即刻生效**：重启对应的音乐 App 即可。

---

## 🛠️ 开发者指南

如果你想为自己喜欢的音乐 App 开发插件，可以参考以下资源：

- **核心接口定义**：参考 `lyric-bridge` 模块。
- **示例代码**：本项目中的 `apple-music` 模块是绝佳的参考对象。
- **提交准则**：确保代码逻辑清晰，不对目标 App 造成稳定性影响。

---

## 🤝 贡献与反馈

<p align="left">
  <a href="https://github.com/proify/LyricProvider/issues">
    <img src="https://img.shields.io/github/issues/proify/LyricProvider?style=flat-square&logo=github" alt="Issues">
  </a>
</p>

### 贡献者

[![Contributors](https://contrib.rocks/image?repo=proify/LyricProvider)](https://github.com/proify/LyricProvider/graphs/contributors)

---

## 📊 统计与历史

### 访问统计

![Visitors](https://count.getloli.com/get/@proify_LyricProvider?theme=minecraft)

### Star 趋势

[![Star History Chart](https://api.star-history.com/svg?repos=proify/LyricProvider&type=Date)](https://star-history.com/#proify/LyricProvider&Date)