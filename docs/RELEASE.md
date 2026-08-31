# Releasing Koode

A release is the APK a family installs and updates from. Getting it wrong in
one specific way — a changed signing key — forces a reinstall, and a reinstall
mid-journey loses the journey. So the whole of this document is really about
one thing: **every Koode build must be signed with the same key.**

## How a release happens

Push a version tag:

```
git tag v6.4.0
git push origin v6.4.0
```

That fires `.github/workflows/release-apk.yml`, which builds the signed
release APK, runs the unit tests, and publishes a GitHub Release with
`Koode.apk` attached. The in-app updater and the README both point at
`releases/latest/download/Koode.apk`, so a new tag is all it takes to offer
every existing user the update.

Pushing to `main` does **not** cut a release. A release is deliberate.

## Signing — the part that matters

Android will only install an update over an existing app if the new APK is
signed with the **same key** as the installed one. A different key is not an
update; it is a different app, and installing it means uninstalling the old
one first — losing the Room database, which for Koode means losing a live
journey. Requirement: an update must never cost a traveller their journey. So
the signing key must be stable across every build, forever.

There are two ways this repo achieves that, checked in order at build time.

### 1. A private release key (preferred for wide distribution)

Set four repository secrets and the build uses them:

| Secret | What it is |
|---|---|
| `KEYSTORE_BASE64` | the keystore file, base64-encoded |
| `KEYSTORE_PASSWORD` | its store password |
| `KEY_ALIAS` | the key alias |
| `KEY_PASSWORD` | the key password |

To create one and load the secrets:

```
keytool -genkeypair -v -keystore koode-release.jks \
  -alias koode -keyalg RSA -keysize 2048 -validity 10950 \
  -storepass 'CHOOSE-A-STRONG-ONE' -keypass 'CHOOSE-A-STRONG-ONE' \
  -dname "CN=Koode, O=Koode, C=IN"

base64 -w0 koode-release.jks > koode-release.b64
# then, in the repo's Settings → Secrets and variables → Actions:
#   KEYSTORE_BASE64  = contents of koode-release.b64
#   KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD = the values above
```

Keep `koode-release.jks` somewhere safe and private. If it is ever lost, no
future build can update an existing install — everyone has to reinstall once.
The `.gitignore` blocks `*.jks` precisely so a real key is never committed by
accident.

### 2. The committed open keystore (zero-setup default)

If those secrets are not set, the build signs with
`apps/android/keystore/koode-open.jks`, which **is** committed to the repo,
with a password (`koode-open`) that is written right here in the open.

This looks alarming and is deliberate. That key protects nothing — anyone can
read it — and it is not trying to. Its only job is signing *continuity*: every
build, on every machine, is signed identically, so an in-place update always
works. It is exactly the trade-off F-Droid makes for the thousands of apps it
signs: for software you sideload from a source you already trust, a stable
public key that preserves your data beats a secret key you might set up wrong
or lose. The cost is that someone could sign a malicious "Koode" update with
the same key — but they would still have to get a family member to install it
from outside the official release page, which is the same trust boundary the
whole sideload model already rests on.

**Rotate to a private key (option 1) before any wider or Play Store
distribution.** Doing so changes the signing certificate, so the first build
after the switch will require a one-time reinstall for existing users; plan it
for a moment when nobody is mid-journey, and tell people.

## Confirming a build is genuine

Every release's notes print the signing certificate's SHA-256 fingerprint. A
careful user can compare it against their installed copy
(`Settings → Apps → Koode`, or `apksigner verify --print-certs Koode.apk`) to
confirm an update came from the same key as their install. The current open
keystore's fingerprint is recorded in the release notes of the first build
that used it.

## The build itself

- `assembleRelease`, **minification off**. R8/ProGuard would need a complete
  set of keep rules for Room, Compose and this app's reflection, and a missing
  rule is a crash that appears only on a real install. For a sideloaded safety
  app, size is not the constraint; not crashing is. If minification is ever
  turned on, it must be proven on a physical device against the checklist in
  `docs/DEVICE_TESTING.md` first.
- The web viewer is published separately by `.github/workflows/pages.yml` on
  any change under `web/`.
