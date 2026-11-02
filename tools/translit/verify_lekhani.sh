#!/usr/bin/env bash
# Compiles the pure-Kotlin LekhaniTransliterator together with LekhaniHarness.kt using the Kotlin compiler
# already in the Gradle cache (no extra project dependencies), then compares it with golden.tsv from the
# reference JS runner (regenerate with tools/translit/make_golden.mjs) and reports load time and ms/word.
#
# Usage: tools/translit/verify_lekhani.sh
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
OUT=build/tools/translit
rm -rf "$OUT" && mkdir -p "$OUT"
"$JAVA_HOME/bin/java" -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -jdk-home "$JAVA_HOME" \
  -no-stdlib -no-reflect -classpath "$STD" -nowarn -d "$OUT" \
  app/src/main/java/dev/zain/znkeyboard/translit/LekhaniTransliterator.kt tools/translit/LekhaniHarness.kt
"$JAVA_HOME/bin/java" -cp "$OUT:$STD" LekhaniHarnessKt app/src/main/assets/translit tools/translit/golden.tsv
