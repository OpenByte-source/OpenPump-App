# Making a release

This is the owner's runbook for github.com/OpenByte-source/OpenPump-App. It covers the
one-time setup of a repository and then what you do each time you publish a version.
**Every step here is owner-only:** it needs the signing key, the repository's admin
settings, or the right to push tags and approve the `release` environment. A contributor
never needs any of it.

How it fits together: the signing key lives on your own computer and on your offline
backups. GitHub holds a copy as secrets inside a protected environment called `release`.
When you push a version tag, the [release workflow](../.github/workflows/release.yml) waits
for you to approve it, then builds the APK, signs it, checks the signature against the
project's certificate, and publishes `OpenPump-<version>.apk` with a `.sha256` checksum on
the Releases page.

---

## One-time setup

Steps 3 to 9 are done once in each repository that publishes releases — for
OpenByte-source/OpenPump-App, before its first release. Steps 1 and 2 are never repeated: the
key already exists.

### 1. The signing key — already made, never make another

The key was made once, and every release is signed with it. Its certificate's SHA-256
fingerprint is published in the README, and the release workflow refuses an APK signed with
anything else (`EXPECTED_CERT` in `release.yml`): a release under another key could never
update the installs already out there. Use the existing `openpump-release.jks` from your
offline backups.

Only if you are starting a fork of your own do you make a key, on your own computer — never
on a CI runner, and never let anyone else make it for you. You need a JDK (the `keytool`
program is in its `bin` folder). A fork also changes `EXPECTED_CERT` in `release.yml` and the
fingerprint in the README to its own.

```
keytool -genkeypair -v -keystore openpump-release.jks -storetype PKCS12 -keyalg RSA -keysize 4096 -validity 10000 -alias openpump
```

It asks for a password and then for a name and organisation. The name is shown to anyone
who inspects the APK's certificate, so use the project's name rather than your own if you
prefer (for example `CN=OpenPump, O=OpenPE`).

Use one strong password. With this keystore type the key's password is the same as the
keystore's, so the same password goes into two secrets below.

### 2. Keep it backed up offline — this matters more than anything else here

Android only installs an update if it is signed with **the same key** as the version
already on the phone. If you lose this file or its password, you can never publish an
update to OpenPump again: every user would have to uninstall (losing their data unless
they made a backup) and install a new app under a new key. There is no recovery, from
Google or anyone else.

So:

- Keep `openpump-release.jks` to at least two places that are not this computer and not
  online — for example two USB sticks kept in different places.
- Write the password down and keep it with them, or in a password manager you trust.
- Check that a copy opens: `keytool -list -v -keystore <copy>.jks` should show the
  `openpump` entry.

Never commit the file. The repository's `.gitignore` already refuses `*.jks`,
`*.keystore` and `keystore.properties`, but don't rely on that — keep the file outside the
repository folder.

### 3. Turn the key into text for GitHub

GitHub secrets are text, so the key file goes up as base64. In PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\openpump-release.jks")) | Set-Clipboard
```

That puts the text on your clipboard for the next step, without leaving a copy on disk.

If you'd rather use `certutil`:

```
certutil -encode openpump-release.jks openpump-release.b64
```

Its output has `-----BEGIN CERTIFICATE-----` / `-----END CERTIFICATE-----` lines around the
text; the workflow ignores them, so you can paste the whole file. Delete
`openpump-release.b64` once the secret is saved.

### 4. Create the protected `release` environment

On GitHub, in OpenByte-source/OpenPump-App: **Settings › Environments › New environment**, name it
exactly `release`, then:

- **Required reviewers:** add yourself (`OpenByte-source`). Nothing in the job can run — and no
  secret can be read — until a reviewer approves that run. If you are the only reviewer,
  leave "Prevent self-review" off, or you won't be able to approve your own release.
- **Deployment branches and tags:** choose "Selected branches and tags" and add a **tag**
  rule `v*.*.*`. Only version tags can then use this environment.

### 5. Add the four secrets to the environment

Still on the `release` environment's page, under **Environment secrets**, add these.
Put them here, **not** under the repository's own "Actions secrets" — repository secrets
can be read by any workflow in the repo; environment secrets only by a run you approved.

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | the base64 text from step 3 |
| `KEYSTORE_PASSWORD` | the password you chose in step 1 |
| `KEY_ALIAS` | `openpump` |
| `KEY_PASSWORD` | the same password again |

(With the GitHub CLI you can do the same thing, e.g.
`gh secret set KEY_ALIAS --env release --body openpump`; for the others, leave out
`--body` and paste the value when it asks, so it doesn't end up in your shell history.)

If any of the four is missing, the release workflow stops before building and says which
one.

### 6. Protect `main` and the version tags

**Settings › Rules › Rulesets**: one ruleset for the default branch `main` (no force pushes,
no deletion, changes through pull requests, a code owner's review for the paths
`.github/CODEOWNERS` names) and one for tags matching `v*` (only you may create, move or
delete them). A tag is what starts a release.

### 7. Turn on GitHub Pages

**Settings › Pages › Source: GitHub Actions.** The [Pages workflow](../.github/workflows/pages.yml)
publishes `docs/site/` on every push to `main` that touches it, and every six hours to
refresh the most-wanted ideas. Its address is `https://openbyte-source.github.io/OpenPump-App/`.

### 8. Turn on Discussions

**Settings › General › Features › Discussions.** The site, the README and the issue forms
link to two categories by their slugs: **Ideas** (`ideas`, where people vote with ▲; its
form is `.github/DISCUSSION_TEMPLATE/ideas.yml`) and
**Q&A** (`q-a`). The Pages workflow reads the Ideas category only, read-only.

### 9. Turn on private vulnerability reporting

**Settings › Security › Private vulnerability reporting.** SECURITY.md and the issue
forms send safety reports there.

---

## Cutting a release

1. **Set the version.** `versionName` in `app/build.gradle.kts` must match the tag you're
   about to push (tag `v0.10.0` ↔ `versionName = "0.10.0"`); the workflow refuses a
   mismatch. `versionCode` takes care of itself — it's 1000 plus the commit count.
2. **Update `CHANGELOG.md`.** Turn the `## [0.10.0] — unreleased` heading into
   `## [0.10.0] — 2026-MM-DD` and check the list under it. That section becomes the
   release notes; if there isn't one, GitHub generates notes from the commits instead.
3. **Merge to `main`** and make sure CI is green there.
4. **Tag and push the tag:**

   ```bash
   git checkout main && git pull
   git tag -a v0.10.0 -m "OpenPump 0.10.0"
   git push origin v0.10.0
   ```

5. **Approve the run.** Open **Actions › Release**; the run waits with "Review
   deployments". Check it's the tag you meant, then approve it. It runs the tests, builds,
   signs, verifies the signature, and creates the release with `OpenPump-0.10.0.apk` and
   `OpenPump-0.10.0.apk.sha256` attached.
6. **Check what came out.** Download the APK, compare its checksum with the `.sha256`
   file (`Get-FileHash OpenPump-0.10.0.apk -Algorithm SHA256` in PowerShell), and install
   it on your phone.

If you need to re-run a release for a tag that already exists (say the first attempt
failed before publishing), use **Actions › Release › Run workflow** and pick the tag — not a branch — under "Use
workflow from". If a release for that tag was already published, delete it on the
Releases page first (keep the tag), or the last step will refuse to create it twice.

### The first signed release

0.10.0 is the first published release (0.9.0 was prepared but never released). Everyone
running OpenPump before it has a build they made themselves, signed with a debug key. The
signed release can't install over it — Android sees a different signer. Anyone moving to the
release has to back up first (**Settings › Device & developer › Developer options**, code
0000, **› Backup & restore**), uninstall, install the release, and restore. The 0.10.0 notes
in CHANGELOG.md say so.

From then on every release installs over the last one, as long as the key is the same.

### The fingerprint

The signing certificate's SHA-256 fingerprint is already in the README and pinned in
`release.yml`, so people who download the APK can check it came from you. The workflow log
prints it in the "Verify the signature" step; `keytool -list -v -keystore
openpump-release.jks` shows it too. If the two ever differ, stop: the run used the wrong key.
