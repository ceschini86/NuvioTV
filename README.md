<div align="center">

  <img src="assets/brand/app_logo_wordmark.png" alt="Nuvio" width="300" />

  <p>
    A free, open-source media app for your phone, your desktop, and the TV you already own.
    <br />
    Bring your own sources. Nuvio turns them into a library with artwork, ratings, subtitles, and your place saved on every screen.
  </p>

  <p><strong>Fork build</strong> with smarter AI subtitles and a refined player seek experience.</p>

  [Website](https://nuvio.tv) · [Releases](https://github.com/ceschini86/NuvioTV/releases/latest) · [Upstream](https://github.com/NuvioMedia/NuvioTV)

</div>

## Get this build

- [Latest APK (universal and ABI splits)](https://github.com/ceschini86/NuvioTV/releases/latest)

This package uses the same `applicationId` as official Nuvio (`com.nuvio.tv`) but a different signing key. Uninstall the official app before installing this fork, or Android will refuse the install.

In-app update checks point at **this** repository’s GitHub Releases.

## What’s different in this fork

### AI subtitles

- **AI translation** (ExoPlayer only) using your own API key — Groq (`gpt-oss-120b`), Gemini (`3.5 Flash Lite`), or Claude (`Haiku 4.5`). Keys stay on-device; multi-key fallback with rate-limit cooldowns.
- **Smart AI subtitles** auto-picks **embedded tracks only**, in this order:
  1. Preferred-language embedded track (no AI)
  2. AI translation from an embedded / original-language track
  3. Classic preferred-language auto-select (addon without AI)
- **Addon AI** only via manual **Translate with AI** (locks MANUAL so auto-select won’t override it).
- **Quality gate:** batches that stay in the source language (or fail coverage) are rejected so the next key/provider can retry — unfinished leftovers are not cached as translations.
- **Match score badges** and an AI source diagnostics panel show why a track was chosen.

Configure under Playback → AI subtitles.

### Player

- Slim, themed **seek / scrub bar**: light feedback on quick taps, expands while you hold and scrub.
- Scrub polish for short seeks vs held scrubbing, so progress feels clearer on TV remotes.

## Build from source

```bash
git clone https://github.com/ceschini86/NuvioTV.git
cd NuvioTV
./gradlew :app:assembleFullDebug
```

Nuvio TV is built with Kotlin, Jetpack Compose, TV Material 3, and Media3. Development requires Android Studio, a JDK, and the Android SDK.

## License

[GNU General Public License v3.0](./LICENSE)
