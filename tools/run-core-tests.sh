#!/usr/bin/env bash
# Compiles and runs the pure-Kotlin core tests (app/src/main/.../core + app/src/test/.../core) WITHOUT the Android SDK,
# using the Kotlin compiler and JUnit bundled in a local Gradle distribution. This is what produced TEST_REPORT.md's numbers.
# Usage: tools/run-core-tests.sh /path/to/gradle-home   (e.g. /opt/gradle-8.14.3)
set -euo pipefail
G="${1:?path to a Gradle distribution}"; L="$G/lib"; ROOT="$(cd "$(dirname "$0")/.." && pwd)"; OUT="$(mktemp -d)"
CP="$L/kotlin-compiler-embeddable-2.0.21.jar:$L/kotlin-stdlib-2.0.21.jar:$L/kotlin-script-runtime-2.0.21.jar:$L/kotlin-reflect-2.0.21.jar:$L/trove4j-1.0.20200330.jar:$L/annotations-24.0.1.jar:$L/kotlin-daemon-embeddable-2.0.21.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar"
TCP="$L/kotlin-stdlib-2.0.21.jar:$L/junit-4.13.2.jar:$L/hamcrest-core-1.3.jar"
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -cp "$TCP" -d "$OUT" \
  "$ROOT"/app/src/main/java/com/nova/browser/core/*.kt "$ROOT"/app/src/test/java/com/nova/browser/core/*.kt
CLASSES=$(cd "$OUT" && find . -name '*Test.class' | sed 's#^\./##; s#\.class$##; s#/#.#g' | tr '\n' ' ')
java -cp "$OUT:$TCP" org.junit.runner.JUnitCore $CLASSES
