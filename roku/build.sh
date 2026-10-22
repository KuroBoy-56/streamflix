#!/bin/sh
# Empaqueta el canal para instalarlo en un Roku (modo desarrollador): dist/VODPro-roku.zip
set -e
cd "$(dirname "$0")"
mkdir -p dist
rm -f dist/VODPro-roku.zip
zip -r -q -X dist/VODPro-roku.zip manifest source components images fonts
echo "Listo: $(pwd)/dist/VODPro-roku.zip"
