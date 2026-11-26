<div align="center">

<img src="docs/banner.png" height="96" alt="sponka" />

### **Created from xnotes, ready for so much more.**

Your new notebook, infinite canvas, e-reader, and sketchbook - *built into a single natural, modular and expandable thinking space*. Take what you need and let the rest stay out of your way.

Created from [xnotes](https://github.com/shardulvs/xnotes-android) - free and open, forever.

App icon by [@failure-404](https://github.com/failure-404).

<p>
<a href="https://github.com/NeDDy3z/Sponka/releases/latest"><img src="https://img.shields.io/github/v/release/NeDDy3z/Sponka?style=flat-square&label=release&color=blue" alt="Release" /></a>
<a href="https://github.com/NeDDy3z/Sponka/releases"><img src="https://img.shields.io/github/downloads/NeDDy3z/Sponka/total?style=flat-square&label=downloads&color=2ea44f" alt="Downloads" /></a>
<a href="https://github.com/NeDDy3z/Sponka/commits"><img src="https://img.shields.io/github/last-commit/NeDDy3z/Sponka?style=flat-square&color=555555" alt="Last Commit" /></a>
<img src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+" />
<a href="https://www.google.com/search?q=LICENSE"><img src="https://img.shields.io/badge/license-MIT-orange?style=flat-square" alt="License: MIT" /></a>
</p>

<p>
<a href="#install">Install</a> |
<a href="ADDITIONS.md">Additions (50+)</a> |
<a href="#roadmap">Roadmap</a> |
<a href="#build-it-yourself">Build</a> |
<a href="#reporting-issues">Issues</a>
</p>

</div>

> [!IMPORTANT]
> **Looking for App Screenshots!**
> We’d love to feature your setup! If you’d like to share a screenshot of your notes, please submit it in [Issue #181](https://github.com/NeDDy3z/Sponka/issues/181). With your permission, we will showcase it in this repository and on our app store listings - with full credit to you.


## Why **sponka**?

[xnotes](https://github.com/shardulvs/xnotes-android) is a thoughtfully built app and we're grateful to Shardul Vikram Singh for creating and sharing it openly. **sponka** (formerly `n-notes`) started as a personal fork to add day-to-day essentials like cloud sync and file change management, and has since grown into its own project.

* **50+ additions** on top of upstream xnotes - see the full list in [**ADDITIONS.md**](ADDITIONS.md).

* **In sync & backwards-compatible** with upstream xnotes whenever possible.

* **Free & open-source** - committed to [long-term support](https://github.com/NeDDy3z/Sponka/discussions/128) with no accounts, ads, or lock-in... EVER.

* **Coming to all platforms** (soon), so you can be productive on all your devices (*using the sync features*).

## Roadmap

Our roadmap lives outside this README so it stays up to date automatically as work progresses:

<p>
<a href="https://github.com/NeDDy3z/Sponka/projects"><img src="https://img.shields.io/badge/GitHub%20Projects-Roadmap%20Board-1f6feb?style=flat-square&logo=github" alt="Roadmap Board" /></a>
<a href="https://github.com/NeDDy3z/Sponka/issues?q=is%3Aopen+label%3Aroadmap"><img src="https://img.shields.io/github/issues/NeDDy3z/Sponka/roadmap?style=flat-square&label=planned%20items&color=8957e5" alt="Planned Items" /></a>
<a href="https://github.com/NeDDy3z/Sponka/milestones"><img src="https://img.shields.io/github/milestones/progress-percent/NeDDy3z/Sponka/1?style=flat-square&label=next%20milestone&color=2ea44f" alt="Next Milestone" /></a>
</p>

## Install

| Channel | Format | Notes | 
| ----- | ----- | ----- | 
| [GitHub Releases](https://github.com/NeDDy3z/Sponka/releases/latest) | Signed APK | Direct download | 
| [Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/NeDDy3z/Sponka) | Signed APK | Auto-updates from GitHub Releases | 

More channels are coming.


### Versioning

Releases follow `{xnotes version}-{sponka version}` (for example, `0.9.10-1.12.1`): the upstream xnotes base version followed by sponka's build revision.

### Build it yourself

Requires **JDK 17** (the project pins Java 17):

```
git clone https://github.com/NeDDy3z/Sponka.git
cd Sponka
JAVA_HOME=/path/to/jdk-17 ./gradlew assembleDebug

```

## Reporting issues

Please open a separate [issue](https://github.com/NeDDy3z/Sponka/issues/new/choose) for each bug or idea instead of bundling them together - it helps us track and resolve everything more efficiently.

## Disclaimer

The sponka team uses LLMs as a tool - primarily for research, code review, and translations. Every AI-assisted change is manually reviewed and tested before release, because AI should assist human work under supervision, never replace it.

Translations are currently fully LLM-generated (due to lack of translators) and will improve over time based on user reports. If you spot a translation issue or wish to contribute, please report it [here](https://github.com/NeDDy3z/Sponka/issues/new?title=Incorrect+Translation&labels=translation&assignees=NeDDy3z&body=Language%3A%0ALocation%3A%0AIncorrect%3A%0AFix%3A).

## Special thanks

- [@shardulvs](https://github.com/shardulvs) for creating xnotes, which sponka is built on.
- [@failure-404](https://github.com/failure-404) for creating the app icon and for a lot of help with managing the repository and coming up with ideas.

## License

Released under the MIT License, the same as upstream. See [LICENSE](LICENSE).

sponka (formerly n-notes) is built on [xnotes](https://github.com/shardulvs/xnotes-android) by Shardul Vikram Singh.
