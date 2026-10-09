#!/usr/bin/env bash
# Builds the static GitHub Pages demo into target/pages: the engine and simulator compiled to JavaScript (TeaVM)
# plus the same index.html the server uses, which talks to the in-page engine when window.orderbook exists.
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -B -q -Ppages -DskipTests process-classes
python3 - <<'PY'
src = open("src/main/resources/demo/index.html").read()
marker = '<script>\n"use strict";'
assert marker in src, "index.html script marker not found"
boot = '<script src="orderbook.js"></script>\n<script>main([]);</script>\n'
open("target/pages/index.html", "w").write(src.replace(marker, boot + marker, 1))
PY
touch target/pages/.nojekyll
echo "Built target/pages"
