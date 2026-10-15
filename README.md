# ZnKeyboard

A modern, privacy-respecting custom keyboard (input method editor) for Android, built with Kotlin and Jetpack Compose. ZnKeyboard pairs a fast custom typing surface with a built‑in **AI rewrite assistant** that polishes your text in place using your own OpenAI (or OpenAI‑compatible) API key.

> Status: actively developed. Built and maintained in the open.

![Platform](https://img.shields.io/badge/platform-Android%2016%2B-3DDC84?logo=android&logoColor=white)
![Language](https://img.shields.io/badge/kotlin-2.3-7F52FF?logo=kotlin&logoColor=white)
![UI](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)

<p align="center">
  <img src="docs/screenshot-keyboard.png" width="200" alt="ZnKeyboard with two shortcut rows, macros, and the Rewrite key">
  <img src="docs/screenshot-shortcuts.png" width="200" alt="Shortcuts screen for arranging shortcut rows and macros">
  <img src="docs/screenshot-settings.png" width="200" alt="Layout and rows settings with theme, height, and shortcut row preview">
  <img src="docs/screenshot-typing.png" width="200" alt="Typing settings with the adaptive touch map">
</p>

---

## Why ZnKeyboard

Most "AI keyboards" send your keystrokes to a vendor's cloud. ZnKeyboard takes the opposite stance:

- **Bring your own key.** AI features call your own OpenAI / OpenAI‑compatible endpoint. There is no ZnKeyboard backend in the middle.
- **Keys never leave the device in plaintext.** API keys are encrypted at rest with the Android Keystore (AES/GCM). A user-created backup can include them only inside a password-encrypted file.
- **AI is opt‑in.** The keyboard is fully usable with the agent disabled; nothing is sent anywhere unless you turn it on and trigger it.

## Features

### 🎯 Adaptive touch: fewer missed keys
ZnKeyboard learns how *your* thumbs actually hit each key and decodes every tap with that, instead of plain rectangles. The full research and numbers are in [docs/typing-accuracy.md](docs/typing-accuracy.md).
- **Key decided at touch-down** with roll hysteresis, so finger drift on lift no longer types the neighbouring key.
- **A personal touch model for each key** (per orientation), with hierarchical shrinkage so rare keys learn from their neighbours.
- **Learns from your corrections:** backspacing a letter and typing its neighbour teaches the model where that tap really belonged. There is no calibration step.
- **Anchored letter prior:** context only settles taps that fall *between* keys. A tap on the centre of a key always types that key. The prior adapts to how you write, Hinglish included.
- **Haptics and a key popup** so you can feel and see each keystroke. Shift tap/double-tap caps lock, auto-capitalisation, and double-space period.
- **See what it learned:** Settings → Typing has a "Try it" field and a per-key touch map showing each key's learned centre and spread.
- In simulation, letter errors drop from about 8% to about 1.3%. Learning stays on the device, is never backed up, and is skipped in password and incognito fields.

### ✍️ AI rewrite assistant ("agent mode")
- Rewrite, polish, or restructure the text in the current field with one tap, then review the result before it replaces anything.
- **Use your own provider:** OpenAI by default (`api.openai.com/v1`), or any OpenAI‑compatible endpoint such as **OpenRouter**, with a configurable model (default `gpt-4o-mini`).
- **App‑aware prompts:** rewrites adapt to where you are typing — e.g. a dedicated WhatsApp profile that preserves casual Romanized Hindi/Urdu code‑switching instead of flattening it.
- **Structured, safe output:** the model is constrained to a JSON `{ "text": ... }` contract, with optional reasoning modes for compatible models.
- Encrypted key storage via the Android Keystore.

### 🧩 Customizable extra button rows
Turn the keyboard into *your* keyboard. Add and arrange extra shortcut rows from a deep menu of keys most keyboards never give you:
- **Cursor & navigation:** arrow keys, `Home`/`End`, `PgUp`/`PgDn`, `Del`.
- **Modifiers & control:** `Ctrl`, `Alt`, `Tab`, `Esc` (latched only for the dispatched action).
- **Custom macros:** chain any key combos (`Ctrl`/`Alt`/`Shift` + letters, digits, F-keys, arrows…) and typed text into one key, e.g. tmux `C-b n` or Vim `Esc` › `:wq` › `Enter`. tmux `C-b`, `C-b n`, `C-b p` ship as editable starters.
- **Every symbol:** all ASCII punctuation plus `€`, `£`, `¥`, `•`.
- **Panel shortcuts:** jump straight to emoji, GIF, clipboard, or snippets, or **Save snippet** to store the current field in one tap.
- **Dedicated Shortcuts screen** with a live preview of both rows, a categorized key palette (tap to add, tap again to remove), swappable row order, and a confirmation before a row is reset to defaults.
- Shortcut rows and the snippet suggestion strip never change the height of the letter keys, so your muscle memory stays put.

### 🏷️ Tag‑based emoji suggestions
- Assign your **own tags** to any emoji (e.g. tag 🫡 as "ok", "done", "got it").
- Tagged emojis surface in search *and* as a one‑tap **contextual suggestion right on the keyboard**, so the emojis you actually use are always a tap away — no scrolling.
- Tap the suggestion right after the word to **swap the word for the emoji** (`done|` → ✅); add a space first to keep both (`done |` → `done ✅`).
- Plus a full **emoji panel** with search, skin‑tone variants, and recents.

### 📋 Clipboard history
- Recent copies are kept on a dedicated panel for quick re‑pasting — no more re‑copying the same address, code, or link.
- On‑device only.

### ✂️ Text snippets
- Save reusable phrases, templates, and boilerplate from settings, the snippet panel, or a **Save snippet** shortcut key.
- **Snippets autocomplete as you type:** start typing a saved snippet and tap the suggestion to finish it.
- **Tag and search** snippets so the right one is instantly findable — ideal for canned replies, email sign‑offs, addresses, or code stubs.

### ⌨️ A real keyboard, done right
- Custom high‑performance typing `View` (not a WebView, not Compose on the hot path) with press feedback tuned to feel like a stock key.
- Gboard‑style **spacebar cursor dragging** for precise caret movement, with a haptic tick per character.
- **Material You theme** that follows your wallpaper and light/dark mode, or a true-black **Classic black** theme. Adjustable key height (extra rows add to the keyboard instead of shrinking keys), space below the keys, and optional filled modifier keys.
- Selection‑aware deletion and correct editor‑action handling (`Done`, `Go`, `Next`, `Search`, `Send`, etc.).
- **GIF picker** powered by [KLIPY](https://klipy.com), with on‑device caching and content filtering.
- **Password-encrypted backup & restore** for settings, clipboard history, learned typing, and configured API credentials.

### 💧 Liquid navigation in the settings app

<p align="center">
  <img src="docs/liquid-nav.gif" width="392" alt="Settings nav bar: the selected tab pinches off its group like a liquid drop and merges back when you leave it">
</p>

The settings app is edge-to-edge: content scrolls behind a floating title pill and a floating nav bar. The selected tab is its own accent pill. Switching tabs makes it **pinch off like a drop of liquid**, and the old tab flows back into its group.
- **No built-in API could do this.** Android's shape morphing only turns one outline into another, and a drop goes from one shape to two. So ZnKeyboard has a small engine of its own.
- **Based on how real liquid behaves:** the neck between pills is drawn as circular arcs, which is the shape liquid takes in 2D. The neck thins faster just before it snaps, and a new bridge grows fast at first when two pills merge.
- **Pure-Kotlin physics core** (`liquidnav/LiquidNavModel`): exact damped springs on a fixed 500 Hz step, so the motion is the same on 60, 90 and 120 Hz screens. Tapping another tab mid-animation continues smoothly from wherever it is.
- **AGSL shader renderer:** crisp 1px anti-aliased edges and a thin outline that wraps the neck as it stretches. Icons and labels sit in a separate layer, so they stay sharp. No per-frame allocation.
- The bar's outer bottom corners follow your phone's rounded screen corners. It respects the system animation scale, TalkBack tab semantics, RTL, and light/dark themes.
- Tune the motion in [previews/liquid-nav-drop.html](previews/liquid-nav-drop.html), which runs the same engine and shader in a browser.

## Architecture

Single‑module Android app (`:app`), package `dev.zain.znkeyboard`.

| Layer | What it does |
| --- | --- |
| `ime/ZnKeyboardInputMethodService` | The `InputMethodService` entry point; owns the input lifecycle and `InputConnection`. |
| `ime/ZnKeyboardView` | The custom keyboard surface — drawing, touch handling, gestures. |
| `ime/AgentReviewView` + prompt assets | AI rewrite UI and the OpenAI request/response flow. |
| `ime/*PanelView` (emoji, GIF, clipboard, snippet) | The expression and productivity panels. |
| `SettingsActivity` (Jetpack Compose, Material 3) | Edge-to-edge settings app with a floating title pill and five tabs: Typing, Layout, Tools (AI / GIF), Snippets, and Backup. |
| `liquidnav/` | Liquid nav bar: `LiquidNavModel` (pure-Kotlin springs and neck physics) and `LiquidNavBar` (Compose layout and AGSL shader). |
| `ShortcutsScreen` + `ShortcutMacros` | Shortcut row editor, key palette, and macro definitions/dispatch. |
| `typing/` | Adaptive touch decoding (`TouchModel`, `CharLanguageModel`, `TouchDecoder`, `TypingLearner`) in pure Kotlin, wired up by `AdaptiveTypingEngine`. |
| `KeyboardSettings` | Single source of truth for persisted settings and encrypted secrets. |
| `constants/` | Centralized dimensions, colors, and defaults. |

Hot paths (`onDraw`, `onTouchEvent`, input dispatch) deliberately avoid allocation, I/O, and blocking work.

## Build

Requirements:
- Android Studio (recent stable) or the Android SDK command‑line tools
- JDK 17
- Android SDK with API 36; `minSdk` is 36 (Android 16)

```sh
# Point Gradle at your SDK
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

# Build a debug APK
./gradlew :app:assembleDebug

# Verify the adaptive typing engine (unit checks + simulated-typist comparison)
curl -sSLO https://norvig.com/ngrams/count_1w.txt
tools/typing/verify_engine.sh count_1w.txt

# Verify the liquid nav engine (springs, neck geometry, 60/90/120 Hz timing, interruption)
tools/liquidnav/verify_liquid_nav.sh
```

Install on a connected device/emulator with `./gradlew :app:installDebug` (or `adb install -r`).

Release builds are signed from `~/.android/keystores/zn-keyboard-release.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`; `storeFile` is relative to that file). Without it, `./gradlew :app:assembleRelease` produces an unsigned APK. An update only installs over an existing app signed with the same key.

## Enable the keyboard

1. Open the **ZnKeyboard** app and follow the prompt, or go to **Settings → System → Languages & input → On‑screen keyboard → Manage keyboards** and enable **ZnKeyboard**.
2. Switch to it from any text field via the keyboard switcher.
3. Tap any editable field to start typing.

## Configure AI rewrite (optional)

1. Open ZnKeyboard settings → **Tools** tab.
2. Choose a provider (OpenAI or OpenRouter), paste your API key, and pick a model.
3. The key is encrypted with the Android Keystore and stored only on your device.
4. Tap the **Rewrite** key on the shortcut row (there by default; re-add it from Layout → Shortcut rows & macros) and review the suggestion before it's applied.

You are billed directly by your chosen provider for your own usage; ZnKeyboard never proxies requests.

## Privacy

- No analytics, no telemetry, no third‑party SDKs for tracking.
- AI requests go straight from your device to the endpoint you configured, using your key.
- GIF search talks to KLIPY only while the GIF panel is open.
- Adaptive-touch learning stores only aggregate statistics (per-key touch offsets, letter-trigram counts) in no-backup storage (excluded from cloud backup, included only in password-encrypted exports), never in password or incognito fields, and can be reset from Settings → Typing.
- Settings and secrets stay on-device; secrets are encrypted at rest. User-created backups are encrypted with a password that is never saved by the app.

## Contributing

Issues and pull requests are welcome. Please:
- Keep changes aligned with the existing `InputMethodService` + custom `View` architecture.
- Treat keyboard changes as device‑facing — verify typing behavior on a real field/device.
- Follow Kotlin / AndroidX / Material 3 conventions already in the codebase.

## License

ZnKeyboard is licensed under the **GNU General Public License v3.0**. See [`LICENSE`](LICENSE).

```
Copyright (C) 2026 Mohammad Zain Shaikh

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
```
