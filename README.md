# ZnKeyboard

A modern, privacy-respecting custom keyboard (input method editor) for Android, built with Kotlin and Jetpack Compose. ZnKeyboard pairs a fast custom typing surface with a built‑in **AI rewrite assistant** that polishes your text in place using your own OpenAI (or OpenAI‑compatible) API key.

> Status: early and actively developed (`v0.1.0`). Built and maintained in the open.

![Platform](https://img.shields.io/badge/platform-Android%2013%2B-3DDC84?logo=android&logoColor=white)
![Language](https://img.shields.io/badge/kotlin-2.3-7F52FF?logo=kotlin&logoColor=white)
![UI](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)

---

## Why ZnKeyboard

Most "AI keyboards" send your keystrokes to a vendor's cloud. ZnKeyboard takes the opposite stance:

- **Bring your own key.** AI features call your own OpenAI / OpenAI‑compatible endpoint. There is no ZnKeyboard backend in the middle.
- **Keys never leave the device in plaintext.** API keys are encrypted at rest with the Android Keystore (AES/GCM) and decrypted only to make a request.
- **AI is opt‑in.** The keyboard is fully usable with the agent disabled; nothing is sent anywhere unless you turn it on and trigger it.

## Features

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
- **Symbols you actually type:** `|`, `/`, `\`, `-`, `=`, and more.
- **Panel shortcuts:** jump straight to emoji, GIF, clipboard, or snippets.
- **Reorderable rows** and adjustable keyboard height so the layout fits your thumbs and your workflow (great for coding and terminal apps on mobile).

### 🏷️ Tag‑based emoji suggestions
- Assign your **own tags** to any emoji (e.g. tag 🫡 as "ok", "done", "got it").
- Tagged emojis surface in search *and* as a one‑tap **contextual suggestion right on the keyboard**, so the emojis you actually use are always a tap away — no scrolling.
- Plus a full **emoji panel** with search, skin‑tone variants, and recents.

### 📋 Clipboard history
- Recent copies are kept on a dedicated panel for quick re‑pasting — no more re‑copying the same address, code, or link.
- On‑device only.

### ✂️ Text snippets
- Save reusable phrases, templates, and boilerplate.
- **Tag and search** snippets so the right one is instantly findable — ideal for canned replies, email sign‑offs, addresses, or code stubs.

### ⌨️ A real keyboard, done right
- Custom high‑performance typing `View` (not a WebView, not Compose on the hot path) with press feedback tuned to feel like a stock key.
- Gboard‑style **spacebar cursor dragging** for precise caret movement.
- Selection‑aware deletion and correct editor‑action handling (`Done`, `Go`, `Next`, `Search`, `Send`, etc.).
- **GIF picker** powered by [KLIPY](https://klipy.com), with on‑device caching and content filtering.
- **Settings backup & restore** as portable JSON.

## Architecture

Single‑module Android app (`:app`), package `dev.zain.znkeyboard`.

| Layer | What it does |
| --- | --- |
| `ime/ZnKeyboardInputMethodService` | The `InputMethodService` entry point; owns the input lifecycle and `InputConnection`. |
| `ime/ZnKeyboardView` | The custom keyboard surface — drawing, touch handling, gestures. |
| `ime/AgentReviewView` + prompt assets | AI rewrite UI and the OpenAI request/response flow. |
| `ime/*PanelView` (emoji, GIF, clipboard, snippet) | The expression and productivity panels. |
| `SettingsActivity` (Jetpack Compose, Material 3) | Enable the keyboard, configure layout, and manage AI / GIF credentials. |
| `KeyboardSettings` | Single source of truth for persisted settings and encrypted secrets. |
| `constants/` | Centralized dimensions, colors, and defaults. |

Hot paths (`onDraw`, `onTouchEvent`, input dispatch) deliberately avoid allocation, I/O, and blocking work.

## Build

Requirements:
- Android Studio (recent stable) or the Android SDK command‑line tools
- JDK 17
- Android SDK with API 36; `minSdk` is 33 (Android 13)

```sh
# Point Gradle at your SDK
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

# Build a debug APK
./gradlew :app:assembleDebug

# Run unit tests
./gradlew :app:testDebugUnitTest
```

Install on a connected device/emulator with `./gradlew :app:installDebug` (or `adb install`).

## Enable the keyboard

1. Open the **ZnKeyboard** app and follow the prompt, or go to **Settings → System → Languages & input → On‑screen keyboard → Manage keyboards** and enable **ZnKeyboard**.
2. Switch to it from any text field via the keyboard switcher.
3. Tap any editable field to start typing.

## Configure AI rewrite (optional)

1. Open ZnKeyboard settings → AI / agent section.
2. Choose a provider (OpenAI or OpenRouter), paste your API key, and pick a model.
3. The key is encrypted with the Android Keystore and stored only on your device.
4. Trigger a rewrite from the keyboard's agent button and review the suggestion before it's applied.

You are billed directly by your chosen provider for your own usage; ZnKeyboard never proxies requests.

## Privacy

- No analytics, no telemetry, no third‑party SDKs for tracking.
- AI requests go straight from your device to the endpoint you configured, using your key.
- GIF search talks to KLIPY only while the GIF panel is open.
- Settings and secrets stay on‑device; secrets are encrypted at rest.

## Contributing

Issues and pull requests are welcome. Please:
- Keep changes aligned with the existing `InputMethodService` + custom `View` architecture.
- Treat keyboard changes as device‑facing — verify typing behavior on a real field/device.
- Follow Kotlin / AndroidX / Material 3 conventions already in the codebase.

See [`AGENTS.md`](AGENTS.md) for detailed implementation and IME‑specific guidelines.

## License

ZnKeyboard is licensed under the **GNU General Public License v3.0**. See [`LICENSE`](LICENSE).

```
Copyright (C) 2026 Mohammad Zain Shaikh

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
```
