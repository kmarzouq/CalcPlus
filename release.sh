#!/usr/bin/env bash
# Cut a signed CalcPlus release and publish it to GitHub Releases (for Obtainium).
#
#   ./release.sh            # tag derived from versionName in build.gradle.kts
#   ./release.sh v0.2.0     # explicit tag
#
# Everything runs through `gh` — no `git` tag pushing. On the first run it
# creates the signing keystore and uploads it (plus the passwords) as GitHub
# Actions secrets; on later runs it just verifies the existing keystore and
# re-dispatches the build.
set -euo pipefail

KS="calcplus-upload.jks"          # git-ignored (*.jks)
ALIAS="upload"
WF="release.yml"

cd "$(git rev-parse --show-toplevel 2>/dev/null || dirname "$0")"

say()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!\033[0m %s\n'   "$*"; }
die()  { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }
b64()  { base64 -w0 2>/dev/null || base64 | tr -d '\n'; }

# ── 0. preflight ──────────────────────────────────────────────────────────
command -v gh      >/dev/null || die "gh is not installed"
command -v keytool >/dev/null || die "keytool not found — install a JDK"
command -v base64  >/dev/null || die "base64 not found"
gh auth status >/dev/null 2>&1 || die "not logged in — run: gh auth login"
REPO=$(gh repo view --json nameWithOwner --jq .nameWithOwner) \
  || die "no GitHub repo detected for this directory"
OWNER=${REPO%%/*}
git check-ignore -q "$KS" || warn "$KS is NOT git-ignored — do not commit it!"
say "repo: $REPO"

# ── 1. signing keystore ───────────────────────────────────────────────────
if [ -f "$KS" ]; then
  say "found $KS — verifying"
  read -rsp "  keystore password: " PW; echo
  keytool -list -keystore "$KS" -storepass "$PW" -alias "$ALIAS" >/dev/null 2>&1 \
    || die "wrong password, or the keystore/alias is bad. Delete $KS to recreate it."
  say "keystore OK"
else
  say "no keystore yet — creating $KS"
  echo "  Choose a password (>= 6 chars) and SAVE IT somewhere permanent:"
  echo "  every future update must be signed with this same key or Android"
  echo "  refuses to install it. There is no recovery if it is lost."
  while :; do
    read -rsp "  new password:   " PW;  echo
    read -rsp "  repeat password: " PW2; echo
    [ "$PW" = "$PW2" ] || { warn "didn't match"; continue; }
    [ ${#PW} -ge 6 ]   || { warn "too short";    continue; }
    break
  done
  keytool -genkeypair -v \
    -keystore "$KS" -storetype PKCS12 -alias "$ALIAS" \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass "$PW" -keypass "$PW" \
    -dname "CN=CalcPlus, O=$OWNER"
  keytool -list -keystore "$KS" -storepass "$PW" -alias "$ALIAS" >/dev/null \
    || die "keystore failed to verify right after creation"
  say "keystore created and verified"
fi
keytool -list -v -keystore "$KS" -storepass "$PW" -alias "$ALIAS" \
  | grep -i 'SHA256:' | head -1 | sed 's/^[[:space:]]*/  cert /'

# ── 2. GitHub Actions secrets ─────────────────────────────────────────────
say "uploading secrets to $REPO"
b64 < "$KS"          | gh secret set SIGNING_KEYSTORE_BASE64 --repo "$REPO"
printf '%s' "$ALIAS" | gh secret set SIGNING_KEY_ALIAS       --repo "$REPO"
printf '%s' "$PW"    | gh secret set SIGNING_STORE_PASSWORD  --repo "$REPO"
printf '%s' "$PW"    | gh secret set SIGNING_KEY_PASSWORD    --repo "$REPO"
gh secret list --repo "$REPO"

# ── 3. tag / version ──────────────────────────────────────────────────────
VER=$(grep -oP 'versionName\s*=\s*"\K[^"]+' android/app/build.gradle.kts | head -1)
TAG="${1:-}"
if [ -z "$TAG" ]; then
  read -rp "  release tag [v${VER:-0.1.0}]: " TAG
  TAG=${TAG:-v${VER:-0.1.0}}
fi
case "$TAG" in v*) ;; *) die "tag must start with 'v' (got '$TAG')";; esac
say "release tag: $TAG"

# ── 4. clear any stale tag / release for this version ─────────────────────
if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  say "removing existing release $TAG"
  gh release delete "$TAG" --repo "$REPO" --yes --cleanup-tag || true
fi
if gh api -X DELETE "repos/$REPO/git/refs/tags/$TAG" >/dev/null 2>&1; then
  say "removed stale tag $TAG"
fi

# ── 5. dispatch the Release workflow ──────────────────────────────────────
PREV=$(gh run list --repo "$REPO" --workflow "$WF" --limit 1 \
       --json databaseId --jq '.[0].databaseId' 2>/dev/null || true)
say "dispatching the Release workflow"
gh workflow run "$WF" --repo "$REPO" -f tag="$TAG"

say "waiting for the run to appear..."
RID=""
for _ in $(seq 1 40); do
  sleep 3
  CUR=$(gh run list --repo "$REPO" --workflow "$WF" --limit 1 \
        --json databaseId --jq '.[0].databaseId' 2>/dev/null || true)
  if [ -n "$CUR" ] && [ "$CUR" != "$PREV" ]; then RID=$CUR; break; fi
done
[ -n "$RID" ] || die "couldn't find the new run — check: gh run list --workflow $WF"

say "run $RID — watching (Ctrl-C is safe; the build keeps running on GitHub)"
if ! gh run watch "$RID" --repo "$REPO" --exit-status --interval 20; then
  echo
  warn "release build failed — tail of the failing step:"
  gh run view "$RID" --repo "$REPO" --log-failed 2>/dev/null | tail -40 || true
  die "fix the problem it reports, then re-run ./release.sh"
fi

# ── 6. done ──────────────────────────────────────────────────────────────
echo
say "published:"
gh release view "$TAG" --repo "$REPO"
APK=$(gh release view "$TAG" --repo "$REPO" --json assets \
      --jq '.assets[] | select(.name|endswith(".apk")) | .apiUrl' 2>/dev/null || true)

cat <<EOF

────────────────────────────────────────────────────────────────────
Release $TAG is live: https://github.com/$REPO/releases/tag/$TAG

On your phone:
  1. Install Obtainium — github.com/ImranR98/Obtainium/releases/latest
     (or from Accrescent). Open the APK, allow the installer, install.
  2. Obtainium → Add App → paste:  https://github.com/$REPO  → Add
  3. It finds $TAG and picks calcplus-*.apk → Install
     (allow Obtainium to install unknown apps when GrapheneOS asks)
  4. Future: bump versionCode + versionName in
     android/app/build.gradle.kts, commit, then run ./release.sh again.
────────────────────────────────────────────────────────────────────
EOF
