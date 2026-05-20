#!/usr/bin/env bash
# xpresso — Spring Boot scaffolder · installer
# usage:
#   curl -fsSL https://raw.githubusercontent.com/Nandobez/Xpresso/main/install.sh | bash
#   bash install.sh --prefix=$HOME/.local --pin=v0.1.0
set -euo pipefail

REPO="${XPRESSO_REPO:-https://github.com/Nandobez/Xpresso.git}"
PREFIX="${XPRESSO_PREFIX:-$HOME/.local}"
REF="${XPRESSO_REF:-main}"
CACHE="${XPRESSO_CACHE:-$HOME/.cache/xpresso}"

for arg in "$@"; do
  case "$arg" in
    --prefix=*) PREFIX="${arg#--prefix=}" ;;
    --pin=*)    REF="${arg#--pin=}" ;;
    --cache=*)  CACHE="${arg#--cache=}" ;;
    -h|--help)
      sed -n '2,6p' "$0"; exit 0 ;;
  esac
done

bold()  { printf '\033[1m%s\033[0m\n' "$*"; }
dim()   { printf '\033[2m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
red()   { printf '\033[31m%s\033[0m\n' "$*" >&2; }

need() { command -v "$1" >/dev/null || { red "✗ missing '$1' in PATH"; return 1; }; }

bold "xpresso installer"
dim  "  prefix: $PREFIX  ·  ref: $REF"
echo

MISSING=0
need git  || MISSING=1
need mvn  || MISSING=1
need java || MISSING=1
[ "$MISSING" -eq 1 ] && { red "install git + mvn + jdk17+ first."; exit 1; }

JV=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')
[ "${JV:-0}" -lt 17 ] && { red "✗ JDK 17+ required (found: $JV)"; exit 1; }
dim "✓ jdk $JV"

mkdir -p "$CACHE"
SRC="$CACHE/src"
if [ -d "$SRC/.git" ]; then
  dim "↻ updating $SRC"
  git -C "$SRC" fetch --quiet origin "$REF"
  git -C "$SRC" reset --quiet --hard "origin/$REF" 2>/dev/null || git -C "$SRC" checkout --quiet "$REF"
else
  dim "↓ cloning $REPO"
  rm -rf "$SRC"
  git clone --quiet --depth=1 --branch "$REF" "$REPO" "$SRC" 2>/dev/null \
    || git clone --quiet --depth=1 "$REPO" "$SRC"
fi

bold "building…"
(cd "$SRC" && mvn -q -DskipTests package)

JAR_SRC="$SRC/target/xpresso.jar"
[ -f "$JAR_SRC" ] || { red "✗ build produced no target/xpresso.jar"; exit 1; }

LIBDIR="$PREFIX/share/xpresso"
BINDIR="$PREFIX/bin"
mkdir -p "$LIBDIR" "$BINDIR"
cp "$JAR_SRC" "$LIBDIR/xpresso.jar"
cat > "$BINDIR/xpresso" <<EOF
#!/usr/bin/env bash
exec java -jar "$LIBDIR/xpresso.jar" "\$@"
EOF
chmod +x "$BINDIR/xpresso"

echo
green "✓ xpresso installed at $BINDIR/xpresso"
echo
case ":$PATH:" in
  *:"$BINDIR":*) : ;;
  *) dim "  $BINDIR is not on PATH — add to your shell rc:"; echo "    export PATH=\"$BINDIR:\$PATH\"" ;;
esac
dim "  try:  xpresso new my-app"
