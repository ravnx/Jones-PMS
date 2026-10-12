# Auto-update from GitHub releases - design

Date: 2026-10-12
Target version: 2.2.0

## Goal

The mailroom iMac finds out about new releases of `ravnx/Jones-PMS` on its own and
updates itself, but only after a person approves. Michael no longer has to visit the
mailroom to swap jars by hand.

Success means: after a one-time manual install of 2.2.0, publishing a GitHub release is
all it takes for the mailroom to be offered that version on its next startup, and
accepting the offer leaves the app running the new version from the same desktop alias.

## Context and constraints

- Mailroom PC: an older iMac on a about 2022 macOS (12/13). Java 17+ from Adoptium.
- Launched from a desktop alias to the jar.
- All data lives in `~/Documents/package_management_system`, not next to the jar, and a
  backup zip is made on every startup. Replacing the jar never touches data.
- The repo is public, so the GitHub API needs no token. Unauthenticated limit is 60
  requests/hour per IP; one check per startup is far below it.
- Releases attach `package-manager-<version>-jar-with-dependencies.jar`. The jar
  manifest carries `Implementation-Version` (already used for the window title).
- `GET /repos/ravnx/Jones-PMS/releases/latest` returns the newest non-draft,
  non-prerelease release, with `tag_name`, `body` (Markdown notes) and per-asset `name`,
  `size`, `browser_download_url` and `digest` (`sha256:<hex>`; present for 2.1.0).
- Startup sends reminder emails in `modelPM.start()`; an update must happen before that
  so the emails come from the new version.

## Install layout (new, recommended)

    ~/Documents/package_management_system/app/PackageManager.jar

with the desktop alias pointing at it. The folder is always writable by the logged-in
user, so updates never need an admin password. The updater replaces whatever jar it is
running from, so this layout is recommended rather than required.

## Startup flow

`Controller.start()` calls `Updater.checkAtStartup()` before `viewFrame.start()` and
`modelPM.start()`. No window is visible yet, so a short blocking check on the EDT is
acceptable.

1. **Eligibility.** Skip silently (with a log line) when any of these hold:
   - no `Implementation-Version` (running from an IDE);
   - the code source is not a `.jar` file;
   - the jar's folder is not writable.
2. **Fetch** `releases/latest` with a 5-second connect and request timeout and a
   `User-Agent` header (GitHub requires one). Any failure (offline, HTTP error, bad
   JSON) is logged and the app starts normally with no message.
3. **Decide.** Offer the update when the release version is greater than the running
   version, the release has a matching jar asset, and the version is not the one stored
   in `update_skip_version`.
4. **Ask.** A dialog shows "Version X is available (you have Y)" and the release notes
   in a scrollable, read-only text area (raw Markdown is fine). Buttons:
   - **Update now** - go to Installing.
   - **Later** - start normally; ask again next startup.
   - **Skip this version** - store `update_skip_version=X` in `config.properties` and
     start normally. A newer release is offered as usual.

## Installing

1. **Download** the asset to `<jar>.new` in the jar's folder, in a background worker,
   with a modal progress dialog and a Cancel button.
2. **Verify**, all of which must pass:
   - downloaded size equals the asset `size`;
   - SHA-256 equals the asset `digest` when one is provided (absent digest is allowed;
     the remaining checks still apply);
   - the file opens as a jar (`JarFile`);
   - its manifest `Implementation-Version` equals the release version.
3. **Swap.** Copy the running jar to `<jar>.previous` (manual rollback copy, replaced on
   each update), then `Files.move(<jar>.new, <jar>, ATOMIC_MOVE, REPLACE_EXISTING)`.
   macOS does not lock a running jar; the running JVM keeps reading the old file's
   contents, so the rename is safe while the app is open.
4. **Relaunch** with the same Java that is running now:
   `ProcessHandle.current().info().command()`, falling back to
   `<java.home>/bin/java`, run as `java -jar <jar>`, then `System.exit(0)`.
5. **On any failure** in steps 1-4 (including Cancel): delete `<jar>.new`, log the
   cause, show "The update could not be installed. Continuing with version Y." (Cancel
   shows nothing), and start normally. If the swap already happened but relaunch fails,
   tell the user to reopen the program, which will then be the new version.

Version 2.0's one-way file format change is the model for future risk: rolling back
the jar is done with `<jar>.previous` plus a backup from `backups/`, as the README
already describes.

## Components

New package `update` under `src/main/java`:

| Class | Responsibility | Depends on |
| ----- | -------------- | ---------- |
| `Version` | Parse `v2.1.0` / `2.1.0` into numeric parts; `compareTo`; reject anything unparsable | - |
| `ReleaseInfo` | Immutable parsed release: version, notes, and the chosen jar asset (name, size, URL, optional SHA-256) | `Version` |
| `GitHubReleaseClient` | Fetch and parse `releases/latest`; pick the asset ending in `-jar-with-dependencies.jar`; base URL is a constructor argument so tests can use a local server | `java.net.http`, Gson |
| `UpdateInstaller` | Download with progress callback and cancellation, verify, swap, relaunch | `ReleaseInfo` |
| `Updater` | Eligibility checks, skip-version preference, dialogs, wiring the above together | all of the above, `PropertyHandler`, Swing |

`Controller` gains one call. Nothing else in the existing code changes.

## Release script

New `release.sh` at the repo root, using `git` and `gh`. It refuses to continue unless:

- the working tree is clean;
- `HEAD` is on `main` and equals `main` on GitHub (checked with
  `gh api repos/ravnx/Jones-PMS/branches/main`, so it works when SSH is blocked);
- the version in `pom.xml` has no existing tag or release.

Then it runs `mvn clean package` (which runs the tests), checks the built jar's
manifest version, and runs
`gh release create v<version> --target <HEAD sha> --title "v<version> Release" --notes-file <file> <jar>`.
Notes come from a file argument so they can be written ahead of time. A `--prerelease`
flag passes through so a release can be published without reaching the mailroom.

## Testing

Unit tests (JUnit 5, matching the existing tests):

- `Version`: ordering (`2.10.0 > 2.9.0`, `2.1 == 2.1.0`), leading `v`, rejection of
  junk.
- `GitHubReleaseClient`: parses a saved copy of the real 2.1.0 response; picks the
  right asset; missing asset; HTTP 404/500; malformed JSON; timeout - against a local
  `com.sun.net.httpserver.HttpServer`.
- `UpdateInstaller` on temp files: success swaps the jar and writes `.previous`; wrong
  size, wrong SHA-256, not-a-jar and wrong manifest version are each rejected with the
  original jar untouched and `.new` removed; absent digest still installs.
- `Updater` decision logic (offer / don't offer, skip-version) without Swing, by keeping
  the decision in a plain method.

Manual end-to-end test before release: build the branch with the pom version set to
`2.0.9`, run that jar from a temp folder, confirm it offers 2.1.0, updates, relaunches
as 2.1.0 and leaves `.previous` behind. Repeat once on the iMac with the alias to
confirm the alias still opens the replaced jar.

## Rollout

1. Release 2.2.0 with `release.sh`.
2. On the iMac, once, by hand: create `~/Documents/package_management_system/app/`, put
   the 2.2.0 jar there as `PackageManager.jar`, point the desktop alias at it, remove
   the old jar.
3. Every later release reaches the mailroom through the updater.

README: replace the installation steps with the macOS layout above, add a short
"Updates" section (what the prompt means, Skip, rollback with `.previous`), and a
"Releasing" section for `release.sh`.

## Out of scope

- Checking for updates while the app is running (only at startup).
- Windows support; the swap relies on macOS/Unix not locking open files.
- Signature verification beyond the HTTPS download and GitHub's SHA-256 digest.
- Automatic rollback.
