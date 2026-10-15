#!/usr/bin/env bash
# Compiles the pure-Kotlin liquid nav engine (LiquidNavModel + its motion constants) together with
# LiquidNavHarness.kt using the Kotlin compiler already in the Gradle cache, then runs the checks:
# spring propagator accuracy, rest layout, bridge tangency, 60/90/120 Hz independence, settling,
# interruption continuity, and the animation-scale-0 snap.
#
# Usage: tools/liquidnav/verify_liquid_nav.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
C="$HOME/.gradle/caches/modules-2/files-2.1"
KV="$(sed -n 's/^kotlin = "\(.*\)"/\1/p' gradle/libs.versions.toml)"
jar() { find "$C/$1" -name "$2" -not -name '*sources*' | sort | tail -1; }
STD="$(jar org.jetbrains.kotlin/kotlin-stdlib "kotlin-stdlib-$KV.jar")"
CP="$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable "kotlin-compiler-embeddable-$KV.jar"):$STD"
CP="$CP:$(jar org.jetbrains.kotlin/kotlin-script-runtime "kotlin-script-runtime-$KV.jar")"
CP="$CP:$(jar org.jetbrains.kotlin/kotlin-reflect "kotlin-reflect-$KV.jar")"
CP="$CP:$(jar org.jetbrains.kotlin/kotlin-daemon-embeddable "kotlin-daemon-embeddable-$KV.jar")"
CP="$CP:$(jar org.jetbrains.kotlinx 'kotlinx-coroutines-core-jvm-*.jar'):$(jar org.jetbrains/annotations 'annotations-*.jar')"
OUT="$(mktemp -d)"
S=app/src/main/java/dev/zain/znkeyboard
"$JAVA_HOME/bin/java" -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -jdk-home "$JAVA_HOME" \
  -no-stdlib -no-reflect -classpath "$STD" -nowarn -d "$OUT" \
  "$S/constants/LiquidNavMotion.kt" "$S/liquidnav/LiquidNavModel.kt" tools/liquidnav/LiquidNavHarness.kt
"$JAVA_HOME/bin/java" -cp "$OUT:$STD" LiquidNavHarnessKt
