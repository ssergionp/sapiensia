#!/usr/bin/env bash
# Installs age and age-keygen v1.3.2 into the directory given as the first argument, after checking the
# SHA-256 of the official release asset (no apt: its mirrors have been down in CI before).
# Supports linux-amd64 (CI) and windows-amd64 (Git Bash on the developer computer).
# The hashes are the SHA-256 digests GitHub publishes for the assets of
# https://github.com/FiloSottile/age/releases/tag/v1.3.2
set -euo pipefail

version="v1.3.2"
dest="${1:?usage: install-age.sh <directory>}"

case "$(uname -s)" in
  Linux)
    asset="age-$version-linux-amd64.tar.gz"
    sha256="cbe24006683f8eb669266162894b9a522a1af52f2665fbc63a4bb032ed26ac10"
    ;;
  MINGW* | MSYS*)
    asset="age-$version-windows-amd64.zip"
    sha256="f48d8f8f9ebe903ab5027ed067652f2cc1db94bc206976430133b905dcd8e8c7"
    ;;
  *)
    echo "install-age.sh: unsupported system $(uname -s); only linux-amd64 and windows-amd64 are pinned" >&2
    exit 1
    ;;
esac

mkdir -p "$dest"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

curl -fsSL --retry 3 --retry-all-errors -o "$tmp/$asset" "https://github.com/FiloSottile/age/releases/download/$version/$asset"
actual="$(sha256sum "$tmp/$asset" | cut -d ' ' -f 1)"
if [[ "$actual" != "$sha256" ]]; then
  echo "install-age.sh: SHA-256 mismatch for $asset (expected $sha256, got $actual)" >&2
  exit 1
fi

if [[ "$asset" == *.zip ]]; then
  powershell.exe -NoProfile -Command "Expand-Archive -Path '$(cygpath -w "$tmp/$asset")' -DestinationPath '$(cygpath -w "$tmp/x")' -Force"
else
  mkdir -p "$tmp/x"
  tar -xzf "$tmp/$asset" -C "$tmp/x"
fi

ext=""
[[ "$asset" == *.zip ]] && ext=".exe"
cp "$tmp/x/age/age$ext" "$tmp/x/age/age-keygen$ext" "$dest/"
echo "age $version installed in $dest (SHA-256 checked)"
