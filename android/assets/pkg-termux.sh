#!@BASH@
# ---------------------------------------------------------------------------
# pkg - installs real Termux packages into this app's own prefix.
#
# Termux's apt cannot be used as-is: apt, dpkg and the packages themselves were
# compiled with /data/data/com.termux/files/usr baked in, a directory only the
# original Termux app may own. This script does what apt does for us:
#
#   * download the official index for this CPU architecture
#   * resolve Depends: recursively
#   * download each .deb from packages.termux.dev
#   * unpack it into $PREFIX
#   * rewrite the termux paths inside the unpacked text files
#
# Placeholders are filled in by TermuxRuntime.writePkgShim().
#
#   @PREFIX@  the app's prefix          @ARCH@    aarch64 / arm / x86_64 / i686
#   @BASH@    this app's bash           @BUSYBOX@ the busybox bundled in the apk
# ---------------------------------------------------------------------------

PREFIX="@PREFIX@"
ARCH="${NISHGAMERR_PKG_ARCH:-@ARCH@}"
BASH="@BASH@"
BUSYBOX="@BUSYBOX@"
REPO="${NISHGAMERR_PKG_REPO:-https://packages.termux.dev/apt/termux-main}"

STATE="$PREFIX/var/lib/nishgamerr"
INDEX="$STATE/Packages"
HAVE="$STATE/installed.txt"
WORK="$PREFIX/tmp/nishgamerr-pkg"
TERMUX_OLD="/data/data/com.termux/files/usr"
TERMUX_HOME="/data/data/com.termux/files/home"

mkdir -p "$STATE" "$WORK" 2>/dev/null
[ -e "$HAVE" ] || : > "$HAVE" 2>/dev/null
[ -f "$INDEX" ] || : > "$INDEX" 2>/dev/null

say()  { echo "$*"; }
warn() { echo "[!] $*"; }
die()  { echo "[!] $*"; exit 1; }

# ── small tools, taken from wherever they exist ─────────────────────────────
if command -v curl >/dev/null 2>&1; then
    FETCH="curl -fsSL --connect-timeout 20 --max-time 900"
elif [ -x "$BUSYBOX" ]; then
    FETCH="$BUSYBOX wget -q -O"
else
    FETCH="wget -q -O"
fi

if command -v gzip >/dev/null 2>&1; then GUNZIP="gzip -dc"
elif [ -x "$BUSYBOX" ]; then GUNZIP="$BUSYBOX gzip -dc"
else GUNZIP=""; fi

if command -v ar >/dev/null 2>&1; then AR="ar"
elif [ -x "$BUSYBOX" ]; then AR="$BUSYBOX ar"
else AR=""; fi

if command -v tar >/dev/null 2>&1; then TAR="tar"
elif [ -x "$BUSYBOX" ]; then TAR="$BUSYBOX tar"
else TAR="tar"; fi

fetch() { # url destination
    if [ "$FETCH" = "curl -fsSL --connect-timeout 20 --max-time 900" ]; then
        $FETCH -o "$2" "$1"
    else
        $FETCH "$2" "$1"
    fi
}

# ── the index ───────────────────────────────────────────────────────────────
refresh() {
    local count
    say "[..] fetching the Termux package index ($ARCH)"
    fetch "$REPO/dists/stable/main/binary-$ARCH/Packages.gz" "$STATE/Packages.gz" \
        || die "could not reach $REPO (is the TV online?)"
    [ -s "$STATE/Packages.gz" ] || die "the index download was empty"
    if [ -n "$GUNZIP" ]; then
        $GUNZIP "$STATE/Packages.gz" > "$INDEX.tmp" || die "the index could not be unpacked"
    else
        die "no gzip available to unpack the index"
    fi
    mv "$INDEX.tmp" "$INDEX"
    count=$(grep -c '^Package:' "$INDEX" 2>/dev/null)
    case "$count" in ''|0) die "the index is empty" ;; esac
    say "[ok] $count packages available"
}

ensure_index() {
    if [ ! -s "$INDEX" ] || [ "$(grep -c '^Package:' "$INDEX" 2>/dev/null || echo 0)" -lt 10 ]; then
        refresh
    fi
}

stanza() { # package name -> its record
    awk -v want="$1" 'BEGIN { RS=""; FS="\n" }
        { for (i = 1; i <= NF; i++) if ($i == "Package: " want) { print; exit } }' "$INDEX"
}

field() { # field name, record on stdin
    awk -v key="$1" '{ if (index($0, key ": ") == 1) { print substr($0, length(key) + 3); exit } }'
}

depends_of() { # record on stdin -> one dependency per line
    field Depends | tr ',' '\n' | sed 's/(.*)//g' | tr -d ' \t' | sed '/^$/d'
}

# Packages that would fight with what the app already provides (its own shell,
# busybox coreutils and this very script).
is_provided() {
    case "$1" in
        dpkg|apt|apt-utils|bash|coreutils|termux-am|termux-tools|termux-exec) return 0 ;;
    esac
    return 1
}

# Unpacked Termux packages expect /data/data/com.termux/files/usr/bin/bash, and
# their scripts are rewritten to point at "$PREFIX/bin/bash". Until the real
# bash is installed, that name has to exist and has to pass its arguments on.
ensure_shell() {
    [ -x "$PREFIX/bin/bash" ] && return 0
    [ -x "$BASH" ] || return 0
    local base shell_arg
    base="${BASH##*/}"
    case "$base" in
        sh|ash|bash|dash|mksh|ksh|zsh) shell_arg="" ;;
        # a plain busybox binary needs to be told which applet to be
        *) shell_arg=" sh" ;;
    esac
    {
        printf '#!%s%s\n' "$BASH" "$shell_arg"
        printf 'exec "%s"%s "$@"\n' "$BASH" "$shell_arg"
    } > "$PREFIX/bin/bash" 2>/dev/null
    chmod 0755 "$PREFIX/bin/bash" 2>/dev/null
    [ -x "$PREFIX/bin/bash" ] && say "[..] \$PREFIX/bin/bash uses the bundled shell for now"
    return 0
}

is_installed() { grep -qx "$1" "$HAVE" 2>/dev/null; }

mark_installed() {
    is_installed "$1" || echo "$1" >> "$HAVE"
}

# ── unpacking ───────────────────────────────────────────────────────────────
unpack_deb() { # path to .deb
    local member taropt
    if command -v dpkg-deb >/dev/null 2>&1; then
        dpkg-deb -x "$1" "$PREFIX" && return 0
        return 1
    fi
    [ -n "$AR" ] || { warn "no dpkg-deb and no ar to unpack $1"; return 1; }
    member=""
    for candidate in data.tar.xz data.tar.gz data.tar.zst data.tar.bz2 data.tar; do
        if $AR t "$1" 2>/dev/null | grep -qx "$candidate"; then member="$candidate"; break; fi
    done
    [ -n "$member" ] || { warn "no data member inside $1"; return 1; }
    case "$member" in
        *.xz)  taropt="xJ" ;;
        *.gz)  taropt="xz" ;;
        *.bz2) taropt="xj" ;;
        *.zst) taropt="--zstd" ;;
        *)     taropt="" ;;
    esac
    ( cd "$PREFIX" && $AR p "$1" "$member" | $TAR -x$taropt ) || return 1
    return 0
}

# busybox grep has no "-I", so the "is this a binary?" decision is made here:
# a NUL byte in the first 4 KB means the file must not be touched. ELF files in
# a Termux package carry /data/data/com.termux/files/usr in their RUNPATH, and
# rewriting that would break them - LD_LIBRARY_PATH covers it at run time.
text_file() {
    local raw stripped
    raw=$(head -c 4096 "$1" 2>/dev/null | wc -c)
    stripped=$(head -c 4096 "$1" 2>/dev/null | tr -d '\000' | wc -c)
    [ "$raw" -eq "$stripped" ]
}

relocate() {
    local dir file
    for dir in "$PREFIX/bin" "$PREFIX/etc" "$PREFIX/libexec" "$PREFIX/share" \
               "$PREFIX/lib" "$PREFIX/var" "$PREFIX/opt"; do
        [ -d "$dir" ] || continue
        for file in $(grep -rl -- "$TERMUX_OLD" "$dir" 2>/dev/null) \
                    $(grep -rl -- "$TERMUX_HOME" "$dir" 2>/dev/null); do
            [ -f "$file" ] || continue
            text_file "$file" || continue
            sed -i "s|$TERMUX_OLD|$PREFIX|g; s|$TERMUX_HOME|$PREFIX/../home|g" "$file" 2>/dev/null
        done
    done
}

# ── installing ──────────────────────────────────────────────────────────────
install_one() { # package name [depth]
    local name depth block dep file deb version
    name="$1"
    depth="${2:-0}"

    if is_installed "$name"; then
        say "[ok] $name is already installed"
        return 0
    fi
    if [ -x "$PREFIX/bin/$name" ] && [ -z "$(stanza "$name")" ]; then
        say "[ok] $name is already available"
        return 0
    fi

    block="$(stanza "$name")"
    if [ -z "$block" ]; then
        warn "no such package: $name"
        say "    pkg search $name    look for a similar name"
        say "    pkg update          refresh a stale index"
        return 1
    fi

    if [ "$depth" -lt 8 ]; then
        for dep in $(printf '%s\n' "$block" | depends_of); do
            if is_provided "$dep"; then continue; fi
            if [ -z "$(stanza "$dep")" ]; then
                say "[..] $dep is not in the repository, skipped"
                continue
            fi
            install_one "$dep" $((depth + 1)) || warn "dependency $dep could not be installed"
        done
    fi

    file="$(printf '%s\n' "$block" | field Filename)"
    [ -n "$file" ] || { warn "the index entry for $name has no Filename"; return 1; }
    deb="$WORK/$(basename "$file")"

    version="$(printf '%s\n' "$block" | field Version)"
    say "[..] downloading $name ${version:+($version)}"
    if ! fetch "$REPO/$file" "$deb"; then
        warn "download failed: $REPO/$file"
        return 1
    fi
    [ -s "$deb" ] || { warn "the download was empty"; return 1; }

    say "[..] unpacking $name"
    if ! unpack_deb "$deb"; then
        warn "could not unpack $deb"
        rm -f "$deb"
        return 1
    fi

    relocate
    rm -f "$deb"
    mark_installed "$name"
    say "[ok] $name ${version:+$version }installed"
    return 0
}

# ── commands ────────────────────────────────────────────────────────────────
cmd="${1:-}"
[ -n "$cmd" ] && shift

case "$cmd" in
    install|add|i)
        [ -z "${1:-}" ] && { say "usage: pkg install <package> [package...]"; exit 1; }
        ensure_index
        ensure_shell
        rc=0
        for name in "$@"; do
            install_one "$name" 0 || rc=1
        done
        exit "$rc"
        ;;
    uninstall|remove|rm)
        [ -z "${1:-}" ] && { say "usage: pkg uninstall <package>"; exit 1; }
        for name in "$@"; do
            if is_installed "$name"; then
                sed -i "/^$name\$/d" "$HAVE" 2>/dev/null
                say "[ok] $name removed from the list (its files stay in $PREFIX)"
            else
                warn "$name is not installed"
            fi
        done
        exit 0
        ;;
    update|refresh|up)
        refresh
        exit $?
        ;;
    upgrade|dist-upgrade)
        ensure_index
        say "[ok] packages are unpacked from the official repository"
        say "     re-run: pkg install <package> to refresh one"
        exit 0
        ;;
    list|ls|installed)
        if [ -s "$HAVE" ]; then sort -u "$HAVE"; fi
        say "-- $(ls "$PREFIX/bin" 2>/dev/null | wc -l) commands in $PREFIX/bin"
        exit 0
        ;;
    search|find|show)
        [ -z "${1:-}" ] && { say "usage: pkg search <text>"; exit 1; }
        ensure_index
        grep -i "^Package: .*$1" "$INDEX" | sed 's/^Package: //' | head -40
        exit 0
        ;;
    files|ls-files)
        [ -z "${1:-}" ] && { say "usage: pkg files <package>"; exit 1; }
        say "$PREFIX holds:"
        ls -1 "$PREFIX"
        exit 0
        ;;
    clean)
        rm -rf "$WORK"/* 2>/dev/null
        say "[ok] caches cleared"
        exit 0
        ;;
    ""|help|-h|--help)
        say "pkg install <pkg>    download a real Termux package into this app"
        say "pkg search <text>    search the official Termux repository"
        say "pkg list             what has been installed here"
        say "pkg update           refresh the package index"
        say "pkg uninstall <pkg>  forget a package"
        say "pkg clean            clear downloaded archives"
        exit 0
        ;;
    *)
        warn "pkg: unknown command '$cmd'"
        say "try: pkg help"
        exit 1
        ;;
esac
