# Agent notes

## Build and install on a phone

- Gradle needs JDK 17+. If `java` isn't on `PATH`, point `JAVA_HOME` at Android Studio's bundled JBR.
- An update installs only if it is signed with the same key as the app already on the device; otherwise it fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Check which key the installed app uses before choosing `installDebug` or a release build:
  `./gradlew :app:assembleRelease && adb install -r app/build/outputs/apk/release/app-release.apk`
- Always install with `-r`. Never `adb uninstall` without asking: it erases the user's app data and settings.

## Verify changes

- There are no unit tests; `./gradlew :app:assembleRelease` succeeding is the compile check.
- After changing `typing/` (`TouchModel`, `CharLanguageModel`, `TouchDecoder`, `TypingLearner`), run the engine check. It needs a word list that is not in the repo:
  `curl -sSLO https://norvig.com/ngrams/count_1w.txt && tools/typing/verify_engine.sh count_1w.txt` (don't commit `count_1w.txt`).
- After changing `liquidnav/LiquidNavModel.kt` or `constants/LiquidNavMotion.kt`, run `tools/liquidnav/verify_liquid_nav.sh`. Tune motion visually in `previews/liquid-nav-drop.html`, which mirrors the engine and shader.
- Keyboard behavior is device-facing; say so when a change still needs testing in a real text field.

## Conventions

- Those four `typing/` files must stay pure Kotlin/JVM (no `android.*` imports): `verify_engine.sh` compiles them without the Android SDK. Android glue belongs in `AdaptiveTypingEngine`.
- The same applies to `liquidnav/LiquidNavModel.kt` and `constants/LiquidNavMotion.kt`; Compose and AGSL code belongs in `LiquidNavBar.kt`.
- Hot paths (`onDraw`, `onTouchEvent`, input dispatch) must not allocate, do I/O, or block.
- Put dimensions, colors, and defaults in `constants/`; persisted settings and secrets go through `KeyboardSettings`.
- Architecture and design background: [README.md](README.md#architecture), [docs/typing-accuracy.md](docs/typing-accuracy.md).

## Signing

- `app/build.gradle.kts` reads release signing from `~/.android/keystores/zn-keyboard-release.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`; `storeFile` is relative to that file). Each developer uses their own key.
- Without that file, `assembleRelease` produces an unsigned APK that cannot be installed.
- Never commit or print keystores or their passwords.
