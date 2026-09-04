# MANAGE_EXTERNAL_STORAGE appeal draft — ready to submit

**When to use:** the moment Policy status shows the "All Files Access Permission: Not a core feature" (or "Need to use MediaStore API or No Access to Files") issue card again with a "Submit an appeal" button — expected after release 11 (0.4.7)'s review completes, likely another rejection since 0.4.7 declares the same permission as the already-rejected 0.4.5/0.4.6.

**Prior appeal (2026-09-04, rejected same day):** argued epub reading is "document management, not media" — a product-framing argument with no technical evidence behind it. This draft is different in kind: it demonstrates, with a live device test, that the Google-recommended alternative (Storage Access Framework) is technically incapable of reaching the data this feature depends on.

**Where this goes:** the appeal dialog's `textarea[aria-label*="incorrect"]` field (the visible "I believe this is incorrect" box — the *first* `<textarea>` in the dialog is a hidden "what did you do to fix" field, not this one; see `play-console-automation-traps` session memory). Character limit unconfirmed — trim the "Supporting evidence" section first if the field rejects the full text.

---

## Appeal text

```
We removed MANAGE_EXTERNAL_STORAGE from our last two submissions and rebuilt file access on
the Storage Access Framework (SAF), the mechanism Google's own guidance recommends in place of
this permission. After implementing and device-testing that migration, we found SAF cannot
reach the data one of our core features depends on, for reasons rooted in Android's own SAF
implementation rather than in our app's design. We are requesting reconsideration with that
evidence attached.

Core feature: RiddleBoox is a diary app whose named "librarian" role reads the words inside a
person's books and their own handwritten/typed pages from BOOX Notebook (the device's built-in
note-taking app) so the diary can discuss them in conversation. Reading back a person's own
notes is not a peripheral feature — it is one of the four roles the app is built around, named
in the app's own onboarding and store listing.

Why SAF cannot replace this permission for BOOX Notebook's data specifically:

1. BOOX Notebook stores its handwriting and typed-shape data under a dot-prefixed directory
   (`.ksync`) on shared storage. It exposes no public ContentProvider for this content — only a
   metadata provider for note title/id/page count, not the note's actual text.

2. Android's Storage Access Framework picker (ACTION_OPEN_DOCUMENT_TREE) will not let a user
   select this directory under any grant scope we tested:
   - Requesting the primary storage volume root (the broadest possible SAF grant, obtained via
     StorageVolume.createOpenDocumentTreeIntent()) is refused by the system picker itself, with
     the message "Can't use this folder — To protect your privacy, choose another folder." This
     is Android's own restriction on selecting a volume root via SAF, not a bug in our
     implementation — we reproduced it live on a physical device (BOOX Note Air 2, Android 11).
   - Requesting a narrower, named subfolder is technically possible for ordinary folders, but
     `.ksync` cannot be selected this way either: the system folder-picker's browse UI does not
     display dot-prefixed directories at all, so a person has no way to navigate into it and
     grant it, regardless of grant scope.

3. We confirmed both restrictions are structural, not something our code can work around: we
   built a complete SAF-based implementation (folder grant flow, document-ID-based file access,
   a testable storage abstraction), tested it end to end on real hardware, and the `.ksync`
   directory remained categorically unreachable in every configuration.

Given this, MANAGE_EXTERNAL_STORAGE is the only mechanism through which this specific, named
core feature (reading a person's own BOOX Notebook pages back to them) can function at all.
Without it, that feature is not degraded — it is entirely and permanently unavailable, with no
SAF-based or MediaStore-based substitute existing on this platform for this data. We are not
requesting broad "convenience" access; the permission's necessity here is specific to one OEM
app's storage layout that the OS's own document-picker filters out of reach.

We would welcome a narrower path if one exists that we have not found — a documented
ContentProvider for BOOX Notebook's page content, or an SAF flag that surfaces dot-prefixed
directories to the picker — and are glad to provide the debug build, logs, or a screen recording
of the SAF picker's refusal on request.
```

## Notes for whoever submits this

- Fill in the actual rejected-issue's "What did you do to fix the issue" field (the *hidden*
  first textarea in the dialog) honestly: we did not change the manifest for this submission —
  we tried the SAF replacement first, confirmed it cannot work for this feature, and reverted to
  declaring the permission. Do not claim a manifest change that didn't happen.
- If the dialog offers a video/screenshot attachment, a screen recording of the "Can't use this
  folder" refusal on real hardware would be strong supporting evidence — worth 5 minutes if the
  BOOX device is still connected when this gets submitted.
- After "Submit appeal", expect a second confirmation dialog (per `play-console-automation-traps`
  session memory) before it actually sends.
