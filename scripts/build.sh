#!/usr/bin/env bash
# Сборка SQLGen Studio: Java-подложка -> jar, Rust core -> тесты.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo '==> Компиляция Java-подложки'
cd "$ROOT/java-backend/sqlgen"
mkdir -p out
javac -d out $(find src/main/java -name '*.java')
jar --create --file sqlgen.jar -C out .
echo "    jar: java-backend/sqlgen/sqlgen.jar"

echo '==> Smoke-тесты Java (парсер + верификатор + генератор)'
javac -d out -cp out src/test/java/org/sqlgen/SmokeRunner.java
java -cp out org.sqlgen.SmokeRunner

echo '==> Тесты Rust core (модели и мост)'
cd "$ROOT/editor/core"
CARGO_BIN="${CARGO:-}"
[ -z "$CARGO_BIN" ] && CARGO_BIN="$(command -v cargo || echo "$HOME/.cargo/bin/cargo")"
"$CARGO_BIN" test --quiet

echo '==> Готово. Запуск редактора: cd editor/src-tauri && cargo tauri dev'
