<h1 align="center">Sii Browser</h1>

<p align="center">An Android browser written in Kotlin (personal project)<br/>Native XML + View UI · No user data collection</p>

<p align="center"><b>English</b> | <a href="README_zh.md">中文</a></p>

<p align="center">
  <a href="LICENSE.md"><img src="https://img.shields.io/badge/license-NonCommercial-blue.svg" alt="License" /></a>
  <a href="https://github.com/wanwuwww/Sii/releases"><img src="https://img.shields.io/github/v/release/wanwuwww/Sii?display_name=tag" alt="Release" /></a>
  <img src="https://img.shields.io/badge/platform-Android-3DDC84.svg" alt="Platform" />
  <img src="https://img.shields.io/badge/Kotlin-2.0.21-7F52FF.svg" alt="Kotlin" />
  <img src="https://img.shields.io/badge/minSdk-26-orange.svg" alt="minSdk" />
</p>

## Overview

Sii is an Android browser built from scratch with Kotlin and native XML layouts. It does not use Compose.

The goal is to make browsing complete: multiple tabs and a tab panel, ad blocking and privacy protection, page translation, a download manager, plus a local MCP service and page capture that external tools can call. The app contains no analytics or crash-reporting SDKs and does not collect user data.

## Features

### Browsing & UI

- Multiple tabs: new, switch, close, restore; tab panel with grid and thumbnails
- Address bar for direct navigation and search, search suggestions, find in page, desktop mode
- Home: glass / minimal / magazine styles; quick site shortcuts; logo as text, icon, or clock
- UI presets and UI DIY: toolbar, tabs, menu, theme, glass intensity
- Floating capsule toolbar, vertical / horizontal menu scrolling, fixed menu position
- Custom new tab page, external link handling

### Privacy & blocking

- Ad blocking: built-in rules, custom rules, rule subscriptions, per-site toggle
- Privacy protection: standard / strict / custom; tracking, social, mining, fingerprint, and content rule lists
- GPC / DNT / Save-Data request headers, URL tracking parameter cleanup, HTTPS upgrade
- Incognito mode: no history, restricted permissions, optional cleanup on exit
- Per-site settings: scripts, images, storage, popups, permissions, request headers

### Translation & reading

- Page translation: online / offline / bilingual, per-site settings
- Reader mode, read aloud, article extraction

### Extensions & tools

- User scripts: UserScript metadata parsing + a subset of GM_* APIs
- Downloads: chunked parallel download, resume, pause/resume, HLS, system downloader
- Bookmarks (folder tree), history, read later
- QR code scanning and generation
- Local MCP service: permission-scoped tools, token authentication, for external AI clients
- Page capture: request metadata plus fetch/XHR status, timing, and response bodies
- Update check: reads GitHub Releases, with an optional silent prompt

## Screenshots

<p align="center">
  <img src="docs/screenshots/home-day.jpg" width="270" alt="Home" />
  <img src="docs/screenshots/tabs.jpg" width="270" alt="Tab panel" />
</p>
<p align="center"><sub>Home · Tab panel</sub></p>

<p align="center">
  <img src="docs/screenshots/menu.jpg" width="270" alt="Menu" />
  <img src="docs/screenshots/browse.jpg" width="270" alt="Browsing" />
</p>
<p align="center"><sub>Menu · Browsing</sub></p>

<p align="center">
  <img src="docs/screenshots/diy.jpg" width="270" alt="UI DIY" />
  <img src="docs/screenshots/mcp.jpg" width="270" alt="MCP service" />
</p>
<p align="center"><sub>UI DIY · MCP service</sub></p>

<p align="center">
  <img src="docs/screenshots/home-night.jpg" width="270" alt="Night mode" />
</p>
<p align="center"><sub>Night mode</sub></p>

## Quick start

### Install

Download `Sii-x.y.z.apk` from [Releases](https://github.com/wanwuwww/Sii/releases).

- arm64-v8a only, Android 8.0 (API 26) or newer
- Release builds use the release signing key and can upgrade over earlier releases; test builds use a different signature and cannot be installed over them

### Build locally

Requires JDK 17, Android SDK 36, and optionally `local.properties` with `sdk.dir`.

```bash
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

Release build (R8 shrinking + resource shrinking, arm64-v8a only, signing configured in `local.properties`):

```bash
./gradlew assembleRelease
# output: app/build/outputs/apk/release/app-release.apk
```

## Architecture

- Kotlin 2.0, AndroidX (AppCompat / Room / DataStore / Lifecycle / Media3 / CameraX)
- UI: native XML layouts + Views (no Compose)
- Architecture: single Activity + Fragments, `AppServices` service locator
- Annotation processing: KSP (Room)

## Module layout

```
com.sii.browser/
├── ui/            Native UI (shell, browser, tabs, settings, bookmarks, history, downloads, rules, scripts)
├── core/webview/  WebView container (client/chrome-client bridges, request interception chain, JS bridges, text extraction)
├── core/adblock/  Ad blocking engine
├── core/rules/    Built-in rules and subscriptions
├── core/privacy/  Privacy management (data clearing / incognito)
├── core/mcp/      Local MCP service (JSON-RPC, tool registry)
├── script/        User scripts (parser + GM bridge)
├── download/      Download engine/service/broadcast/JS bridge
├── data/          DataStore settings + Room (bookmarks/history/downloads/favorites/rules/scripts)
└── di/            AppServices facade
```

## Third-party components

| Component | License |
|---|---|
| AndroidX (core/appcompat/fragment/lifecycle) | Apache License 2.0 |
| Room / DataStore | Apache License 2.0 |
| OkHttp | Apache License 2.0 |
| Google ML Kit Translate / Language ID | Google ML Kit Terms of Service |
| Kotlin standard library / coroutines | Apache License 2.0 |

See [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) for the full list and copyright notices.

## Feedback

If you run into a problem or have a suggestion, please open an [Issue](https://github.com/wanwuwww/Sii/issues) with reproduction steps and device information.

## Disclaimer

This project is independently developed and provided "as is", without any express or implied warranty. You are responsible for any risk arising from its use.

## License

The source code is public under a non-commercial license (not an OSI-approved open source license):

- Personal learning, research, non-commercial use, and modification are permitted;
- Commercial use, app store distribution, and impersonating the name or icon are not permitted;
- Redistribution must retain this license and attribution, and be shared under the same license.

See [LICENSE.md](LICENSE.md) for details.
