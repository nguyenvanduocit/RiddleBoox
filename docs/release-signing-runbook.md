# Release signing runbook

Source of truth for how a release AAB gets signed, where the upload key lives, and what to
do before that key is lost — nothing here should have to be re-derived by reading
`app/build.gradle.kts` cold.

## Where the key lives

`app/build.gradle.kts` resolves the keystore properties file in this order:

1. `RIDDLEBOOX_KEYSTORE_PROPERTIES` env var, if set — points at any `keystore.properties` file
   (this is the CI hook: point it at a path a runner writes from a secret, nothing else to wire up).
2. Otherwise `~/.riddleboox/keystore/keystore.properties` (outside the repo on purpose — this repo
   has no history of being safe to store secrets in; see `app/build.gradle.kts`'s comment on
   `keystorePropertiesFile`).

`keystore.properties` has four keys:

```properties
storeFile=/absolute/path/to/upload-keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

If that file doesn't exist, `signingConfigs.release` in `app/build.gradle.kts` is never created,
and the `release` build type falls back to no signing config — `./gradlew bundleRelease` still
succeeds but produces an unsigned AAB Play Console will reject.

## Producing a signed release AAB

```bash
./gradlew bundleRelease
```

Output: `app/build/outputs/bundle/release/app-release.aab`. Verify it's actually signed before
uploading:

```bash
jarsigner -verify -verbose app/build/outputs/bundle/release/app-release.aab | tail -5
```

(`jar verified.` — if instead you see "jar is unsigned", `keystoreProperties` didn't resolve;
check `RIDDLEBOOX_KEYSTORE_PROPERTIES` / `~/.riddleboox/keystore/keystore.properties` first.)

## First-time keystore creation

Only if `~/.riddleboox/keystore/upload-keystore.jks` doesn't exist yet on the machine doing the
release:

```bash
mkdir -p ~/.riddleboox/keystore
keytool -genkeypair -v \
  -keystore ~/.riddleboox/keystore/upload-keystore.jks \
  -alias riddleboox-upload \
  -keyalg RSA -keysize 2048 -validity 10000
```

Then write `~/.riddleboox/keystore/keystore.properties` with the four keys above, matching what
`keytool` was just given.

## Why losing this key is unrecoverable

Google Play identifies "the same app" by upload-key signature (or by Play App Signing, which
re-signs with a Google-held key but still needs the *upload* key to authenticate each upload).
Lose `upload-keystore.jks` with no backup and there is no way to push another update to the same
Play Store listing — the only recourse is publishing under a new package name, losing every
install, rating, and review the original had. This is a single point of failure sitting on
whichever machine happens to hold `~/.riddleboox/keystore/`.

**Back it up before the first real release**, not after: copy `~/.riddleboox/keystore/` (both
`upload-keystore.jks` and `keystore.properties`) to at least one location that survives this
machine being wiped or lost — a password manager's file attachment or an encrypted archive in
cold storage both work; a plaintext copy in cloud sync does not, since `keyPassword`/
`storePassword` sit next to the key file in the same directory.

## CI

No CI currently drives this build (see the testing/CI gap noted separately). When one exists, it
should never receive the keystore file itself in plaintext repo state — write it from a secrets
store to a runner-local path at job start, set `RIDDLEBOOX_KEYSTORE_PROPERTIES` to that path, and
let the existing `app/build.gradle.kts` resolution do the rest; nothing about the Gradle config
needs to change for a CI runner versus a local machine.
