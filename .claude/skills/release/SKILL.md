---
name: release
description: Automate a RiddleBoox release from the current checkout by bumping the Android version, testing and building the signed AAB, pushing the release commit, and uploading the bundle to Google Play for review without interactive confirmation.
---

# RiddleBoox release

Use this project-local skill for `/release`. The caller has authorized the complete release pipeline. Work from the current checkout and finish the release without asking questions or requesting confirmation.

## Non-interactive contract

- Never call `AskUserQuestion`, ask for confirmation, or pause for a choice.
- Make only deterministic, repository-scoped changes described below, plus the Play Console upload.
- If a required precondition is missing or unsafe, stop and print the exact failure, the affected path or command, and the concrete remediation. Do not guess credentials, signing keys, repository ownership, versions, or release contents.
- Never use `git reset --hard`, `git checkout --`, `git clean`, force-push, tag overwrite, or any command that discards existing work.
- Preserve unrelated user changes. Do not stash them, amend their commits, or silently include them in a release.
- Do not print secret values. Never commit `local.properties`, keystores, signing properties, API keys, tokens, or generated build output.

## Scope and repository conventions

RiddleBoox ships exclusively through Google Play (`com.riddleboox.app`, dev account "vominh" 5830950916337104671, production track `4697936911789772498`). A release means: a version bump, a green test gate, a signed AAB, a pushed release commit, and the bundle submitted for review in the Play Console.

Conventions:

- Version source: `app/build.gradle.kts`, the first `versionName = "..."` and `versionCode = ...` declarations.
- Release branch: `main`, remote `git@github.com:nguyenvanduocit/RiddleBoox.git`.
- The landing and guide pages carry no per-release version numbers; releases do not touch `pages/`.

The signing source of truth is `docs/release-signing-runbook.md`. Read it before building. It uses `RIDDLEBOOX_KEYSTORE_PROPERTIES` or `~/.riddleboox/keystore/keystore.properties`. Never create a replacement keystore during a release.

## Version selection

The first argument is optional:

- no argument: patch bump, for example `0.4.2` to `0.4.3`;
- `patch`, `minor`, or `major`: semver bump from the current `versionName`;
- an exact `X.Y.Z`: use that version.

Reject any other argument. Reject an exact version that is not greater than the current `versionName`. For every release, set `versionName` to the target version and increase `versionCode` by exactly one — Play refuses any version code it has already seen, including codes attached to discarded drafts.

## Pipeline

### 1. Preflight

1. Confirm the checkout is this repository and the working branch is `main`.
2. Confirm the signing properties exist per the runbook, and `jarsigner` is available.
3. Inspect `git status --short` and the complete relevant diff. Existing changes may be included only when they are clearly release-ready and belong to this release; if the worktree mixes unrelated or secret-looking changes, stop and list the files.
4. Google Play requirements that block a release at submission time — verify before building:
   - `targetSdk` must be at least the current Play minimum (36 as of 2026-09).
   - The dependency tree must not contain API bypass SDKs (e.g. `org.lsposed.hiddenapibypass`) — Play's pre-review quick checks hard-reject them.

### 2. Bump, test, and build

1. Bump `app/build.gradle.kts` as specified above.
2. Run the test gate: `./gradlew testDebugUnitTest`. Note: `RiddleStateMachineTest` has a documented wall-clock flake under machine load (`docs/known-issues.md` #8) — on a failure there, re-run the single test before suspecting the change.
3. Build the bundle: `./gradlew bundleRelease`.
4. Verify `app/build/outputs/bundle/release/app-release.aab` is non-empty, `jarsigner -verify` reports `jar verified.`, and `aapt2 dump badging` on a fresh `assembleRelease` APK (or bundletool on the AAB) shows the intended `versionCode`/`versionName`.

### 3. Commit and push

1. Run `git diff --check`, review the staged diff, and stage explicit paths only.
2. Create one commit `chore: release v<version>` and push to `origin main`.

### 4. Upload to Google Play and submit for review

Use ego-browser against the Play Console (the flow is documented in the session memory note `play-console-automation-traps`):

1. Open the app dashboard first, then the production track: `/app/4974427342592693637/tracks/4697936911789772498?tab=releases`.
2. Discard any stale unsent release, create a new release, and upload the AAB (capture the hidden file input by patching `HTMLInputElement.prototype.click`, then `DOM.setFileInputFiles`).
3. Confirm the processed bundle row shows the new `versionCode (versionName)` and the intended target SDK.
4. Fill release notes inside `<en-US>...</en-US>` tags, keep the auto-generated release name, proceed to preview, and Save. Deobfuscation/native-symbol warnings are non-blocking.
5. From Publishing overview, submit the changes for review and wait for the pre-review quick checks to finish. Success is the state "Your changes are now in review" with no issues listed; a "changes not yet submitted" state with an issue is a hard stop to report.

Google's review (typically up to 7 days) publishes the release automatically — managed publishing is off.

## Completion report

At the end, verify `git status --short` and report concisely:

- released version and version code;
- commit hash on `origin/main`;
- test and signature verification results;
- the Play Console state you observed after submission ("Your changes are now in review"), plus any warnings;
- any non-blocking verification uncertainty.

If any stage stops, report the stage, exact command/error, files already changed, and the safest concrete remediation. Never claim the release succeeded unless the pushed commit, the signed AAB, and the in-review Play submission have each been verified.
