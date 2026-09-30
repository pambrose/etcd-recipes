#!/bin/sh
# tlc.sh — model-check the TLA+ specs in this directory with TLC.
#
#   ./specs/tlc.sh                      every model (every *.cfg)
#   ./specs/tlc.sh CountBarrier.cfg     just the named models
#
# A model is a <Module>[<Variant>].cfg next to <Module>.tla. TLC is downloaded once into
# .tools/ (gitignored), pinned by its SHA-256. Exits non-zero if any model finds an error.
set -eu

TLC_VERSION=1.7.4
TLC_SHA256=936a262061c914694dfd669a543be24573c45d5aa0ff20a8b96b23d01e050e88

cd "$(dirname "$0")"
jar=.tools/tla2tools.jar

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

if [ ! -f "$jar" ] || [ "$(sha256 "$jar")" != "$TLC_SHA256" ]; then
  mkdir -p .tools
  echo "Downloading TLC $TLC_VERSION"
  curl -sSfL -o "$jar.part" "https://github.com/tlaplus/tlaplus/releases/download/v$TLC_VERSION/tla2tools.jar"
  if [ "$(sha256 "$jar.part")" != "$TLC_SHA256" ]; then
    echo "TLC download failed its checksum" >&2
    rm -f "$jar.part"
    exit 1
  fi
  mv "$jar.part" "$jar"
fi

meta=$(mktemp -d)
trap 'rm -rf "$meta"' EXIT

if [ "$#" -gt 0 ]; then models="$*"; else models=$(ls ./*.cfg | sed 's|^\./||'); fi

failed=""
for cfg in $models; do
  base=${cfg%.cfg}
  # The model's module: the longest <Module>.tla whose name starts the config's name
  module=""
  for tla in ./*.tla; do
    name=$(basename "$tla" .tla)
    case "$base" in
      "$name"*) if [ ${#name} -gt ${#module} ]; then module=$name; fi ;;
    esac
  done
  if [ -z "$module" ]; then
    echo "== $cfg: no matching .tla" >&2
    failed="$failed $cfg"
    continue
  fi

  echo "== $cfg ($module.tla)"
  log="$meta/$base.log"
  if java -XX:+UseParallelGC -cp "$jar" tlc2.TLC -workers auto -metadir "$meta/$base" -config "$cfg" "$module.tla" >"$log" 2>&1 &&
    grep -q "Model checking completed. No error has been found." "$log"; then
    grep -E "^[0-9]+ states generated|^Finished in" "$log" | sed 's/^/   /'
  else
    cat "$log"
    failed="$failed $cfg"
  fi
done

if [ -n "$failed" ]; then
  echo "TLC found errors in:$failed" >&2
  exit 1
fi
