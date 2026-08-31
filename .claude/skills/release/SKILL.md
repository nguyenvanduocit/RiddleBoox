---
name: release
description: Automate a RiddleBoox release from the current checkout by bumping the Android version, testing and building signed APK/AAB artifacts, syncing the landing and guide pages, publishing the GitHub release, and deploying the pages without interactive confirmation.
---

# RiddleBoox release

Use this project-local skill for `/release`. The caller has authorized the complete release pipeline. Work from the current checkout and finish the release without asking questions or requesting confirmation.

## Non-interactive contract

- Never call `AskUserQuestion`, ask for confirmation, or pause for a choice.
- Make only deterministic, repository-scoped changes described below.
- If a required precondition is missing or unsafe, stop and print the exact failure, the affected path or command, and the concrete remediation. Do not guess credentials, signing keys, repository ownership, Cloudflare projects, DNS settings, versions, or release contents.
- Never use `git reset --hard`, `git checkout --`, `git clean`, force-push, tag overwrite, release deletion, or any command that discards existing work.
- Preserve unrelated user changes. Do not stash them, amend their commits, or silently include them in a release.
- Do not print secret values. Never commit `local.properties`, keystores, signing properties, API keys, tokens, or generated build output.

## Scope and repository conventions

This is an Android application. There is no API/backend module in this repository, so “build API” means build the distributable Android release artifacts: a signed APK and signed AAB.

Use these conventions discovered in the repository:

- Version source: `app/build.gradle.kts`, the first `versionName = "..."` and `versionCode = ...` declarations.
- Release branch: `main`.
- GitHub remote: `git@github.com:nguyenvanduocit/RiddleBoox.git` (`nguyenvanduocit/RiddleBoox`).
- GitHub CLI: `gh`.
- Landing deployment: `./scripts/deploy-landing.sh`, which deploys `pages/` to the existing Cloudflare Pages project.
- Public pages: `https://riddleboox.pages.dev/` and `https://riddleboox.aiocean.io/`.
- Release APK asset name: `RiddleBoox-v<version>.apk`.
- Release AAB asset name: `RiddleBoox-v<version>.aab`.

The signing source of truth is `docs/release-signing-runbook.md`. Read it before building. It uses `RIDDLEBOOX_KEYSTORE_PROPERTIES` or `~/.riddleboox/keystore/keystore.properties`. Never create a replacement keystore during a release.

## Version selection

The first argument is optional:

- no argument: patch bump, for example `0.4.0` to `0.4.1`;
- `patch`, `minor`, or `major`: semver bump from the current `versionName`;
- an exact `X.Y.Z`: use that version.

Reject any other argument. Reject an exact version that is not greater than the newest existing `vX.Y.Z` tag or that already has a tag/release. The release tag is `v<version>`.

For every successful release:

1. Update only the first version declarations in `app/build.gradle.kts`.
2. Set `versionName` to the target version.
3. Increase `versionCode` by exactly one from the current value, including for an exact version bump.
4. Re-read the file and verify there is exactly one intended first-match update and that the resulting values are the target version and expected version code.

Use a small deterministic script or an equivalent narrowly scoped edit. Do not replace all matching text in the repository.

## Pipeline

Run the following stages in order. Use `set -euo pipefail` in shell snippets and preserve command exit codes.

### 1. Preflight

From the repository root:

1. Confirm the checkout is this repository and the working branch is `main`. If not, stop with the branch and repository identity.
2. Run `git fetch --tags origin` to refresh tag information. This is read-only.
3. Confirm `origin` resolves to the expected RiddleBoox GitHub repository.
4. Check `gh auth status`, `wrangler whoami`, `jarsigner`, and the signing properties described in the runbook. Authentication checks may expose account names, but must not expose tokens or secret contents.
5. Inspect `git status --short`, `git diff --check`, and the complete relevant diff before editing. Existing changes may be included only when they are clearly release-ready and belong to this release. If the worktree mixes unrelated or secret-looking changes with release changes, stop and list the files to resolve; never ask whether to continue.
6. Confirm the target tag does not exist locally or remotely and that no GitHub release already exists for it.
7. Confirm the required source files and deployment script exist and are non-empty. Do not create missing infrastructure automatically.

Do not use `git add -A` merely because the worktree is dirty. Stage an explicit list after reviewing every staged path.

### 2. Bump, test, and build

After selecting and validating the target version:

1. Bump `app/build.gradle.kts` as specified above.
2. Run the project test gate:

   ```sh
   ./gradlew testDebugUnitTest --no-daemon
   ```

3. Build both release artifacts:

   ```sh
   ./gradlew assembleRelease bundleRelease --no-daemon
   ```

4. Require these non-empty outputs:

   - `app/build/outputs/apk/release/app-release.apk`
   - `app/build/outputs/bundle/release/app-release.aab`

5. Verify both artifacts with `jarsigner -verify -verbose`. Continue only when each verification reports `jar verified.` and the command succeeds. An unsigned or unverifiable artifact must never be published.
6. Copy the two verified artifacts to a temporary directory outside the repository using the exact release asset names. Do not add build outputs to Git.

If the build fails, leave the version edit and diagnostics in place for inspection and stop. Do not undo the edit automatically.

### 3. Sync the landing and guide pages

Inspect the current page content before changing it. Keep the existing design and copy style.

- In `pages/index.html`, update the visible Android download/version note to the target version while preserving its platform and minimum-version information.
- In `pages/guide.html`, update the download card to `RiddleBoox-v<version>.apk`, the APK size from the newly built APK in a human-readable MB value, and `versionCode <code>`. Do not leave an older release number in that card.
- In `pages/script.js`, preserve the repository’s current download convention. If it points to `/releases/latest`, keep that behavior. If it contains a pinned or placeholder release URL, update it to the new release according to the surrounding convention.
- If page JavaScript is changed, increment its cache-busting query consistently in every page that references it, including `index.html` and `guide.html`.
- Read the guide and landing page for user-visible release claims affected by the release. Update stale claims only when the source code or release changes prove they are stale; do not invent feature descriptions.

Run static checks after page edits:

```sh
test -s pages/index.html
test -s pages/guide.html
test -s pages/styles.css
test -s pages/script.js
```

Check that the target version appears in the intended visible locations and that the guide card contains the exact target APK filename and version code. Do not commit build output or temporary asset files.

### 4. Review and commit

Before committing:

1. Run `git diff --check`.
2. Inspect `git status --short` and the complete diff for `app/build.gradle.kts`, `pages/index.html`, `pages/guide.html`, `pages/script.js`, and any already-reviewed release-related changes.
3. Scan the staged file list and diff for secrets and accidental generated files. If anything suspicious appears, stop without staging it.
4. Run the narrowest relevant test again if source or version changes invalidate the earlier test result.
5. Stage explicit release paths only. Include reviewed pre-existing release changes when they are part of the intended release; omit unrelated work.
6. Create one commit with message `chore: release v<version>`.
7. Push the commit with `git push origin main`.

Do not create the GitHub release until the commit is pushed successfully.

### 5. Publish GitHub release

Verify once more that the tag is absent, then create the release from the pushed `main` commit:

```sh
gh release create "v<version>" \
  --target main \
  --title "RiddleBoox v<version>" \
  --generate-notes \
  "<temp-dir>/RiddleBoox-v<version>.apk" \
  "<temp-dir>/RiddleBoox-v<version>.aab"
```

Never overwrite an existing tag or release. After creation, verify it with `gh release view "v<version>" --json url,isDraft,assets` and require a published, non-draft release containing both exact asset names.

### 6. Deploy and verify pages

Only after the GitHub release is published, run:

```sh
./scripts/deploy-landing.sh
```

Use the existing Cloudflare Pages project and credentials. Do not create a project, change DNS, or change account configuration automatically.

Verify the deployed landing page and guide contain the new release data, for example with `curl -fsSL` and focused searches for the target version, exact APK asset name, and version code. Check both the `pages.dev` URL and the custom domain when reachable. A custom-domain check failing only because of transient DNS/TLS/network behavior must be reported separately from a failed deployment.

If GitHub release creation succeeds but deployment fails, do not delete or alter the GitHub release. Report the release URL and the exact deployment command to retry, then stop.

## Completion report

At the end, verify `git status --short` and report concisely:

- released version and version code;
- commit and tag;
- GitHub release URL and the two asset names;
- landing and guide deployment URLs;
- test, signature, and remote verification results;
- any non-blocking verification uncertainty.

If any stage stops, report the stage, exact command/error, files already changed, and the safest concrete remediation. Never claim a release succeeded unless the GitHub release, both signed assets, pushed commit, and page deployment have each been verified.
