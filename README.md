<div align="center">

<!-- Swap to assets/icon.png if you prefer the standalone app icon -->
<img src="docs/banner.png" height="96" alt="sponka" />

### **Born from xnotes, ready for so much more.**
Your new notebook, infinite canvas, e-reader, and sketchbook — *built into one living, modular thinking space*. Take what you need and let the rest stay out of your way.

Built on [xnotes](https://github.com/shardulvs/xnotes-android) — free and open, forever.

*App icon by [@failure-404](https://github.com/failure-404).*

<p>
  <a href="https://github.com/NeDDy3z/Sponka/releases/latest"><img src="https://img.shields.io/github/v/release/NeDDy3z/Sponka?style=flat-square&label=release&color=blue" alt="Release" /></a>
  <a href="https://github.com/NeDDy3z/Sponka/releases"><img src="https://img.shields.io/github/downloads/NeDDy3z/Sponka/total?style=flat-square&label=downloads&color=2ea44f" alt="Downloads" /></a>
  <a href="https://github.com/NeDDy3z/Sponka/commits"><img src="https://img.shields.io/github/last-commit/NeDDy3z/Sponka?style=flat-square&color=555555" alt="Last Commit" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+" />
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-orange?style=flat-square" alt="License: MIT" /></a>
</p>

<p>
  <a href="#install">Install</a> •
  <a href="ADDITIONS.md">Additions (50+)</a> •
  <a href="#roadmap">Roadmap</a> •
  <a href="#build-it-yourself">Build</a> •
  <a href="#reporting-issues">Issues</a>
</p>

</div>

---

## Why sponka

[xnotes](https://github.com/shardulvs/xnotes-android) is a thoughtfully built app and we're grateful to Shardul Vikram Singh for creating and sharing it openly. **sponka** (formerly `n-notes`) started as a personal fork to add day-to-day essentials like cloud sync and file change management, and has since grown into its own project.

- **50+ additions** on top of upstream xnotes — see the full list in **[ADDITIONS.md](ADDITIONS.md)**.
- **In sync & backwards-compatible** with upstream xnotes whenever possible.
- **Free & open-source** — committed to [long-term support](https://github.com/NeDDy3z/Sponka/discussions/128) with no accounts, ads, or lock-in... EVER.

## Roadmap

Our roadmap lives outside this README so it stays up to date automatically as work progresses:

<p>
  <a href="https://github.com/NeDDy3z/Sponka/projects"><img src="https://img.shields.io/badge/GitHub%20Projects-Roadmap%20Board-1f6feb?style=flat-square&logo=github" alt="Roadmap Board" /></a>
  <a href="https://github.com/NeDDy3z/Sponka/issues?q=is%3Aopen+label%3Aroadmap"><img src="https://img.shields.io/github/issues/NeDDy3z/Sponka/roadmap?style=flat-square&label=planned%20items&color=8957e5" alt="Planned Items" /></a>
  <a href="https://github.com/NeDDy3z/Sponka/milestones"><img src="https://img.shields.io/github/milestones/progress-percent/NeDDy3z/Sponka/1?style=flat-square&label=next%20milestone&color=2ea44f" alt="Next Milestone" /></a>
</p>

## Install

| Channel | Format | Notes |
| :--- | :--- | :--- |
| [GitHub Releases](https://github.com/NeDDy3z/Sponka/releases/latest) | Signed APK | Direct download or track via [Obtainium](https://obtainium.imranr.dev/) |

### Versioning

Releases follow `{xnotes version}-{sponka version}` (for example, `0.9.7-0.48`): the upstream xnotes base version followed by sponka's build revision.

<details>
<summary><strong>Build it yourself</strong> (requires JDK 17)</summary>

```bash
git clone [https://github.com/NeDDy3z/Sponka.git](https://github.com/NeDDy3z/Sponka.git)
cd Sponka
JAVA_HOME=/path/to/jdk-17 ./gradlew assembleDebug
```
<details>

## Reporting issues

Please open a separate [issue](https://github.com/NeDDy3z/Sponka/issues/new/choose) for each bug or idea instead of bundling them together — it helps us track and resolve everything more efficiently.

## Disclaimer

The sponka team uses LLMs as a tool—primarily for research, code review, and translations. Every AI-assisted change is manually reviewed and tested before release, because AI should assist human work under supervision, never replace it.

Translations are currently fully LLM-generated (due to lack of translators) and will improve over time based on user reports. If you spot a translation issue or wish to contribute, please report it [here](https://github.com/NeDDy3z/Sponka/issues/new?title=Incorrect%20Translation&labels=translation&assignees=NeDDy3z&body=Language:%0ALocation:%0AIncorrect:%0AFix:).

## License

Released under the MIT License, the same as upstream. See [LICENSE](LICENSE).

sponka (formerly n-notes) is built on [xnotes](https://github.com/shardulvs/xnotes-android) by Shardul Vikram Singh.