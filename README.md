<div align="center">

<!-- Replace the src path below with the actual path to your app icon in the repo -->
<img src="assets/icon.png" alt="sponka icon" width="120" />

# sponka
### **Introducing sponka…**
**a vector-based handwriting-first notebook, e-reader, infinite canvas, pixel-based sketch *(soon)*, and notes management app for Android.**  
Built on [xnotes](https://github.com/shardulvs/xnotes-android), ready for so much more.

*App icon by [@failure-404](https://github.com/failure-404).*

[![Release](https://img.shields.io/github/v/release/NeDDy3z/n-notes?style=flat-square&color=blue)](https://github.com/NeDDy3z/n-notes/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-orange.svg?style=flat-square)](LICENSE)
[![Platform: Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-brightgreen.svg?style=flat-square)](#installation)

</div>

---

## What makes sponka different?
**sponka** aims to become the most complete handwriting-first notes app, ready for anything.  
**FREE** the community and fully **OPEN, FOREVER**.

## Why did we create sponka?
[xnotes](https://github.com/shardulvs/xnotes-android) is an amazing and very thoughtfully developed app, and the sponka team is extremely grateful for all the work the developer, Shardul Vikram Singh, has put into it. After using it extensively, our developer, Neddy, created the original fork (`n-notes`) to add missing features for themselves, such as cloud sync and file change management. The team does their best to keep sponka up to date with the original repository, and the app will always be backwards compatible.

## Long-term support
The sponka team is dedicated to maintaining sponka to the best of their ability and to always keep it fully free and open source for the community.

## Additions to the original xnotes
sponka adds **50+ features** on top of xnotes, while **10+** *(as of 2026.11.01)* of them were later implemented in xnotes.  
See the full list in **[ADDITIONS.md](ADDITIONS.md)** — many more features are on their way!

## Versioning
Releases are named `{xnotes version}-{sponka version}`, for example `0.9.7-0.48`: the upstream xnotes release the build is based on, then sponka's version, bumped on every published build.

## Installation

| Channel | Format |
| :--- | :--- |
| [GitHub Releases](https://github.com/NeDDy3z/n-notes/releases) | Signed APK |

## Build it yourself
Requires **JDK 17** (the project pins Java 17):

```bash
git clone [https://github.com/NeDDy3z/n-notes.git](https://github.com/NeDDy3z/n-notes.git)
cd n-notes
JAVA_HOME=/path/to/jdk-17 ./gradlew assembleDebug
