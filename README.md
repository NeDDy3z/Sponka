<p align="center"><img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" alt="sponka icon" /></p>

<h1 align="center">sponka</h1>

<p align="center">
  A vector-based handwriting-first notebook, e-reader, infinite canvas, pixel-based sketch and notes management app for Android. Built on xnotes, ready for so much more.<br />
  Introducing sponka. App icon by <a href="https://github.com/failure-404">@failure-404</a>.
</p>

<p align="center">
  <a href="https://github.com/NeDDy3z/n-notes/releases/latest"><img src="https://img.shields.io/github/v/release/NeDDy3z/n-notes?style=flat-square&label=release&color=blue" alt="Release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-orange?style=flat-square" alt="License" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+" />
</p>

---

## Why did we create sponka?

xnotes is an amazing and very thoughtfully developped app and the sponka team is extremely grateful for all the work the developer has put into it. After using it extensively, Neddy created the original fork (n-notes) to add missing features for themselves, such as cloud sync and file change management. The team does their best to keep sponka up-to-date with the original repository and the app will always be backwards compatible.

## Long-term support:

The sponka team is dedicated to maintaining sponka to the best of their ability and to always keep it fully free and open-source for the community.

## Additions to the original xnotes

sponka adds more then 50+ features on top of xnotes, while 10+ (2026.11.01) of them were later implemented in xnotes as well. See the full list in [ADDITIONS.md](ADDITIONS.md). Many more features are on their way!

## Versioning

Releases are named `{xnotes version}-{sponka version}`, for example `0.9.7-0.48`: the upstream xnotes release the build is based on, then sponka's version, bumped on every published build.

## Install

| Channel | |
|---|---|
| [GitHub Releases](https://github.com/NeDDy3z/n-notes/releases/latest) | Signed APK |

### Build it yourself

Requires **JDK 17** (the project pins Java 17):

```bash
git clone https://github.com/NeDDy3z/n-notes.git
cd n-notes
JAVA_HOME=/path/to/jdk-17 ./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## Reporting issues

When reporting bugs, issues or ideas, please split them into multiple smaller issues instead of one big one. That way they're easier for me to track and I won't miss any bug or requested feature.

## Disclaimer

The sponka team's developper, Neddy, uses LLMs during development. This is mainly for research, code review and translations. Any and every change an LLM makes is manually reviewed/tested before it goes into a release.

Translations are fully LLM-generated and get fixed over time based on user reports. If you spot a translation issue, please report it [here](https://github.com/NeDDy3z/n-notes/issues/new?title=Incorrect%20Translation&labels=translation&assignees=NeDDy3z&body=Language:%0ALocation:%0AIncorrect:%0AFix:).

## License

Released under the MIT License, the same as upstream. See [LICENSE](LICENSE).

sponka (formerly n-notes) is a fork of [xnotes](https://github.com/shardulvs/xnotes-android) by Shardul Vikram Singh.
