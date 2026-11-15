#!/usr/bin/env bash
# Offline checks, no server needed: builds the plugin, runs ConfigCheck on the stock config.yml,
# checks lore.yml placeholders, and checks every weapon has its own resource-pack entry.
# Run from anywhere (Git Bash on Windows works). Needs Java 21, Maven, Python 3 with PyYAML.
set -euo pipefail
cd "$(dirname "$0")/.."
MVN="mvn -B -q ${MVN_OFFLINE:-}"
$MVN package
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=provided)
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; esac
mkdir -p target/check
javac -nowarn -cp "$CP${SEP}target/classes" -d target/check tools/audit/CheckHarness.java 2>/dev/null
java -cp "$CP${SEP}target/classes${SEP}target/check" CheckHarness src/main/resources/config.yml src/main/resources/config.yml
python3 tools/audit/lore_check.py
python3 tools/pack_placeholders.py --check
