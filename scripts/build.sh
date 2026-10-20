#!/usr/bin/env bash
# Сборка и проверка ReaT: Java-подложка (только валидация) + Rust-ядро редактора.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== [1/4] javac: компиляция java-backend/reagen =="
mkdir -p java-backend/reagen/out
javac -d java-backend/reagen/out $(find java-backend/reagen/stubs java-backend/reagen/src/main/java java-backend/reagen/src/test/java -name '*.java')

echo "== [2/4] Smoke-тесты Java (генерация + 4 уровня проверки) =="
java -cp java-backend/reagen/out -Dreagen.stubs=java-backend/reagen/stubs net.reagen.SmokeRunner

echo "== [3/4] jar для Rust-моста =="
(cd java-backend/reagen/out && jar cf ../../reagen.jar .)
echo "built reagen.jar"

echo "== [4/4] cargo test: Rust-ядро редактора =="
(cd editor/core && PATH="$HOME/.cargo/bin:$PATH" cargo test -q)

echo "E2E: пример процедуры через VerifyCli:"
java -cp java-backend/reagen/out -Dreagen.stubs=java-backend/reagen/stubs \
  net.reagen.verify.VerifyCli < procedures/give_diamond.json
echo
echo "BUILD OK"
