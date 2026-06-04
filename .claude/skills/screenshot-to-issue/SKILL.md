---
name: screenshot-to-issue
description: Attach a maintainer-sent screenshot/mockup to a GitHub issue so implementer/reviewer agents can reference the real image — without committing it to the repo. Use whenever the maintainer shares an image (in ~/inbox/pocketshell/ or a path) as app feedback.
argument-hint: [image-path] [issue-number]
---

# Screenshot → Issue attachment

The maintainer sends screenshots/mockups (dropped into `~/inbox/pocketshell/`
on the Hetzner box, or pasted). Standing preference (2026-06-04): **attach every
such image to the relevant GitHub issue** so agents can reference the real
picture, and do it **without a git push** (don't commit feedback images to the
repo — the maintainer asked not to).

The mechanism: upload the image as an asset to a dedicated **prerelease**
(`feedback-assets`) and embed its download URL in the issue with `![](...)`.
Release assets are not part of the git history, so there's no repo push.

## 1. One-time: the holding prerelease

Reuse the `feedback-assets` prerelease as the asset bucket. Create it once if
missing (it's a prerelease so it never shows as "Latest" and never triggers the
Build workflow — that only fires on `v*` tags):

```bash
gh release view feedback-assets >/dev/null 2>&1 || \
  gh release create feedback-assets --prerelease \
    --title "Feedback assets" \
    --notes "Image attachments for issues. Not a real release — assets only."
```

## 2. Read the image first

ALWAYS `Read` the image before attaching, so the issue text describes what it
actually shows (narration/on-screen state can disagree):

```
Read <image-path>      # e.g. ~/inbox/pocketshell/<timestamp>-<hash>.png
```

## 3. Upload as a release asset + get the URL

Give it a stable, descriptive name (the issue + a slug), not the raw inbox hash:

```bash
ASSET="issue-<N>-<slug>.png"
cp "<image-path>" "/tmp/$ASSET"
gh release upload feedback-assets "/tmp/$ASSET" --clobber
URL="https://github.com/alexeygrigorev/pocketshell/releases/download/feedback-assets/$ASSET"
```

## 4. Embed in the issue

Either put `![<slug>]($URL)` in the issue body at creation
(`gh issue create --body ...`), or attach to an existing issue as a comment:

```bash
gh issue comment <N> --body "Maintainer screenshot:

![<slug>]($URL)"
```

The image now renders inline on GitHub for any agent reading the issue.

## 5. Clean up the inbox

Per the standing request, delete the source image from the inbox after it's
attached so they don't pile up:

```bash
rm "<image-path>"
```

## Notes

- This supersedes committing feedback images under `docs/` — no repo push for
  screenshots. (Genuine design-reference mockups that belong in the docs set
  may still be committed deliberately, but routine feedback screenshots use the
  release-asset path.)
- If the same image updates, re-upload with `--clobber` (same asset name keeps
  the URL stable).
- See [[loom-feedback]] for the video-feedback flow; this skill is its
  still-image sibling.
