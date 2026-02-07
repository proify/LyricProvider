# Lyric Extensions for Lyricon

![Platform](https://img.shields.io/badge/Platform-Android-brightgreen?style=flat&logo=android)
![Release](https://img.shields.io/github/v/release/proify/LyricProvider?style=flat&color=blue&logo=github)
![Size](https://img.shields.io/github/repo-size/proify/LyricProvider)
![Downloads](https://img.shields.io/github/downloads/proify/LyricProvider/total?style=flat&color=orange)
![License](https://img.shields.io/github/license/proify/LyricProvider?style=flat)
![Last Commit](https://img.shields.io/github/last-commit/proify/LyricProvider?style=flat)

## 🎵 Supported Platforms

These providers use **Xposed Hooking** to extract real-time lyric data directly from the following
music applications.

### Core Integrations (Global & Major)

| Platform                   | Identifier          | Capabilities                           |
|:---------------------------|:--------------------|:---------------------------------------|
| 🎧 **Spotify**             | `spotify-music`     | Standard lyrics (static)               |
| 🍎 **Apple Music**         | `apple-music`       | Dynamic lyrics, Translations           |
| ☁️ **NetEase Cloud Music** | `163-music`         | Dynamic lyrics, Translations           |
| 🐧 **QQ Music**            | `qq-music`          | Dynamic lyrics, Translations           |
| ⚡ **Poweramp**             | `poweramp-music`    | Online matching & Embedded lyrics      |
| 🧊 **LX Music**            | `lx-music`          | Lyric translations                     |
| 🐶 **Kugou / Lite**        | `kugou-music`       | **Requires "Car Mode" enabled in-app** |
| 📻 **Kuwo Music**          | `kuwo-music`        | **Requires "Car Mode" enabled in-app** |
| 🧂 **Salt Player**         | `salt-player-music` | Uses Flyme (Meizu) Lyric standard      |

### Universal & Special Modules

| Module Name                  | Identifier (ID)  | Use Case                                                               |
|:-----------------------------|:-----------------|:-----------------------------------------------------------------------|
| ☁️ **Cloud Provider**        | `cloud-provider` | Generic matching via online lyric databases                            |
| 📱 **Meizu Support**         | `meizu-provider` | Works with any player supporting Meizu Status Bar lyrics               |
| 🧂 **In-car lyrics Support** | `car-provider`   | Suitable for players that have been adapted for in-car lyrics display. |

### 🚀 Natively Supported (No Plugin Required)

The following players have built-in support for the Lyricon protocol and work out of the box:

* **ConePlayer (光锥音乐)**: [Official Homepage](https://coneplayer.trantor.ink/)

---

## 📥 Installation

> [!IMPORTANT]
> This is an **extension package**. You must have the [Lyricon](https://github.com/proify/lyricon)
> main application installed for this to function.

1. **Download**: Grab the latest APK from
   the [Releases page](https://github.com/proify/LyricProvider/releases).
2. **Activate**: Install the APK, open your **LSPosed Manager**, and enable the specific Provider
   module.
3. **Configure Scope**: In LSPosed, select the target music apps you wish to hook (e.g., Spotify,
   Apple Music).
4. **Apply**: Force stop and restart your music app to activate the lyrics.

---

## 🛠️ Developer Guide

We welcome community contributions! If you'd like to help adapt more music players, please check our
documentation.

Refer to
the [Development Guide](https://github.com/proify/lyricon/blob/master/lyric/bridge/provider/README-English.md).

---

## 🤝 Contributors

[![Contributors](https://contrib.rocks/image?repo=proify/LyricProvider)](https://github.com/proify/LyricProvider/graphs/contributors)

## 📊 Analytics

### Traffic Trends

![Visitors](https://count.getloli.com/get/@proify_LyricProvider?theme=minecraft)

### Star Growth

[![Star History Chart](https://api.star-history.com/svg?repos=proify/LyricProvider&type=Date)](https://star-history.com/#proify/LyricProvider&Date) 