#!/usr/bin/env bash
# Pure-Java release-selection regression checks, including the public GitHub feed.
set -euo pipefail
repo_dir="$(cd -- "$(dirname -- "$0")/../.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf -- "$test_dir"' EXIT
curl -fLsS --max-time 30 https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar -o "$test_dir/json.jar"
curl -fLsS --max-time 30 'https://api.github.com/repos/arcuataone/Telegram/releases?per_page=100' -o "$test_dir/releases.json"
javac -cp "$test_dir/json.jar" -d "$test_dir" "$repo_dir/TMessagesProj/src/main/java/org/telegram/messenger/QiQiGramRelease.java" "$repo_dir/tools/tests/QiQiGramReleaseTest.java"
java -cp "$test_dir:$test_dir/json.jar" QiQiGramReleaseTest "$test_dir/releases.json"
