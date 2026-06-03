# Release process

End-to-end checklist for cutting a Kformik release. **Two paths are supported:**

- **Automated (preferred):** push a `v*` tag to GitHub. `.github/workflows/release.yml` runs the full pipeline (verify → sign → stage → close → promote → GitHub release) on a `macos-14` runner. See *§ Automated release via GitHub Actions* below for the one-time setup.
- **Manual (fallback):** the rest of this document is the manual procedure — useful when GitHub Actions can't run the release (network restrictions, runner outage, debugging a failed automated release).

Both paths talk to the same Sonatype endpoints and produce the same artifacts; only the driver differs.

## Automated release via GitHub Actions

### One-time setup (do this once, before your first automated release)

1. **GitHub repository secrets** — `Settings → Secrets and variables → Actions → New repository secret`. Add:
   - `SONATYPE_USERNAME` — your Sonatype user-token username
   - `SONATYPE_PASSWORD` — your Sonatype user-token password
   - `SIGNING_KEY` — your ASCII-armored GPG private key (the full `-----BEGIN PGP PRIVATE KEY BLOCK-----` block). Paste it as-is; GitHub will store it intact.
   - `SIGNING_PASSWORD` — the passphrase for `SIGNING_KEY`
2. **(Recommended) `release` environment** — `Settings → Environments → New environment → release`. Add yourself as a required reviewer. The release workflow references `environment: release`, so once configured, every release run pauses on the `Promote to Maven Central (IRREVERSIBLE)` step until you click Approve in the GitHub Actions UI. If you skip this setup, the release runs unattended; comment out the `environment: release` line in `.github/workflows/release.yml` to allow that.

### Cutting a release

1. Bump `kformikVersion` in `gradle.properties` from `X.Y.Z-SNAPSHOT` to the release version `X.Y.Z`. Commit, push.
2. Annotated-tag the release commit: `git tag -a vX.Y.Z -m "Kformik vX.Y.Z"`, then `git push origin vX.Y.Z`.
3. The push triggers `.github/workflows/release.yml`. Watch its progress in the Actions tab.
4. If the `release` environment is configured, approve the run when prompted (the irreversible promote step is gated behind this).
5. After Maven Central sync (10–30 min), verify the artifact is visible at <https://repo1.maven.org/maven2/io/github/apdelrahman1911/kformik/X.Y.Z/>. The README badge will catch up automatically.

### Dry-run (test the workflow without publishing)

Run the workflow via `workflow_dispatch` with `dry_run = true` (Actions tab → Release → Run workflow). It exercises the verification + sign + staging-upload phases and stops before close/promote. The staging repository is dropped automatically.

### What the workflow does NOT do

- It does **not** bump back to a `-SNAPSHOT` after release. If you want that, edit `gradle.properties` manually after the release lands.
- It does **not** push commits or modify `main` — only reads from the tag.
- It does **not** create the tag — you push the tag; the workflow consumes it.

If a release fails partway through, the workflow's failure-cleanup step drops the staging repo via `bulk/drop`. Failed-run logs are uploaded as a GitHub Actions artifact (`release-failure-logs`) for inspection.

---

## Manual release (fallback)

This is the original procedure — runs everything from your local machine. Documented for the case where the automated workflow is unavailable.

## Snapshot

- **Current version:** `1.8.0` (single source of truth: `gradle.properties` → `kformikVersion=…`)
- **Group:** `io.github.apdelrahman1911` (verified via GitHub identity on Maven Central; single source of truth: `gradle.properties` → `kformikGroup=…`)
- **Modules published:** `:kformik` (KMP — JVM, Android, iosX64, iosArm64, iosSimulatorArm64), `:kformik-compose` (KMP umbrella — Android, Desktop JVM, iosX64, iosArm64, iosSimulatorArm64; Android consumers auto-resolve the `android` variant via Gradle module metadata), `:kformik-forms` (KMP — same target set as `:kformik-compose`; added in v1.8.0), `:kformik-ksp` (JVM JAR)
- **Local-Maven path:** `~/.m2/repository/io/github/apdelrahman1911/<artifact>/<version>/`
- **External target:** Sonatype **Central Publisher Portal** via the OSSRH-Staging-API bridge (`ossrh-staging-api.central.sonatype.com`) → Maven Central. The exact endpoints are configured in the root `build.gradle.kts` (`nexusPublishing { … }`), which is the source of truth.
- **Docs jars:** Dokka HTML, wired into the `-javadoc` classifier of every publication

## Prerequisites

| Tool | Version | Why |
|---|---|---|
| JDK | 17+ | Gradle toolchain target |
| Gradle | 8.13 (wrapper) | included via `./gradlew` |
| Kotlin | 2.0.21 | plugins declared in root build |
| Xcode | any (≥ 16 tested) | only needed for iOS builds |
| Android SDK (compileSdk 34) | — | Android targets |
| GPG | 2.x | release signing |
| `gh` CLI (optional) | 2.x | GitHub release creation |

## Required environment variables (release time only)

None are required for **local** builds or `publishToMavenLocal`. The following are read by the build only if set; otherwise the corresponding step is a no-op.

| Variable | Used by | Purpose |
|---|---|---|
| `SIGNING_KEY` | `signing { useInMemoryPgpKeys(...) }` in each module | ASCII-armored GPG private key (the full `-----BEGIN PGP PRIVATE KEY BLOCK-----` block) |
| `SIGNING_PASSWORD` | same | passphrase for the GPG key |
| `SONATYPE_USERNAME` | `nexusPublishing { sonatype { ... } }` in root build | OSSRH user-token username |
| `SONATYPE_PASSWORD` | same | OSSRH user-token password |
| `sonatypeStagingProfileId` (Gradle property) | same | optional — the staging profile ID associated with the `io.github.apdelrahman1911` group |

Use a `~/.gradle/gradle.properties` (machine-scoped, **never committed**) or a CI secret manager. **Do not put credentials in this repo.**

## GPG / signing setup

```bash
# 1. Generate a key (one-time)
gpg --full-generate-key       # 4096-bit RSA, no expiry recommended

# 2. List keys to find your key ID
gpg --list-secret-keys --keyid-format=long

# 3. Publish the public key to a keyserver (required by Maven Central)
gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>

# 4. Export the ASCII-armored private key for use in CI / shell env
gpg --armor --export-secret-keys <KEY_ID> > /tmp/kformik-signing.key
export SIGNING_KEY="$(cat /tmp/kformik-signing.key)"
export SIGNING_PASSWORD="<passphrase>"
rm /tmp/kformik-signing.key
```

When `SIGNING_KEY` + `SIGNING_PASSWORD` are present, `./gradlew publishToMavenLocal` signs every artifact and produces `*.asc` files. Without them, signing is silently skipped — which is exactly what keeps day-to-day development unblocked.

## Sonatype / OSSRH setup

One-time (a real maintainer must do this, not the build). The legacy `issues.sonatype.org` JIRA
"New Project" flow and the `s01.oss.sonatype.org` host are decommissioned — use the Central
Publisher Portal:

1. Verify the `io.github.apdelrahman1911` namespace at <https://central.sonatype.com> (Account →
   Namespaces). For an `io.github.<github-username>` group this is auto-verifiable via GitHub identity.
2. Generate a **Portal user token** at <https://central.sonatype.com/usertoken> (Account → Generate
   User Token). Set `SONATYPE_USERNAME` / `SONATYPE_PASSWORD` to the token username/password.
   Note: an old OSSRH/`s01` token returns HTTP 401 against the configured bridge endpoint.
3. `sonatypeStagingProfileId` is optional under the bridge — it can be left unset. The
   gradle-nexus publish-plugin (v2) handles the bridge upload/close; there is no manual upload step.

## Local verification (run before tagging)

```bash
./gradlew clean
./gradlew :kformik:allTests :kformik:iosSimulatorArm64Test \
          :kformik:compileKotlinIosX64 :kformik:compileKotlinIosArm64 \
          :kformik-compose:assembleRelease :kformik-compose:testReleaseUnitTest \
          :kformik-ksp:test :sample-android-app:assembleDebug \
          :sample-android-app:testDebugUnitTest \
          :examples:compileKotlin publishToMavenLocal
# Expected: BUILD SUCCESSFUL, 0 warnings.

for ex in login nested async typed fieldlevel dependent debounced wizard fieldarray schema; do
    ./gradlew :examples:run -PrunExample=$ex -q
done
# Expected: all 10 examples print their final-state line.

# Inspect the locally-published artifacts:
ls ~/.m2/repository/io/github/apdelrahman1911/*/$(grep '^kformikVersion=' gradle.properties | cut -d= -f2)/
# Expected: each module has -<version>.jar, -<version>-sources.jar, -<version>-javadoc.jar, .pom

# Verify the Dokka HTML is actually inside the javadoc jar:
V=$(grep '^kformikVersion=' gradle.properties | cut -d= -f2); jar tf ~/.m2/repository/io/github/apdelrahman1911/kformik-jvm/$V/kformik-jvm-$V-javadoc.jar | head
# Expected: index.html + Dokka HTML site assets
```

## External publish (SNAPSHOT)

For SNAPSHOT versions (`*-SNAPSHOT`), Sonatype's snapshot repo is open immediately — no staging required.

```bash
export SIGNING_KEY="$(cat ~/keys/kformik-signing.key)"
export SIGNING_PASSWORD="<passphrase>"
export SONATYPE_USERNAME="<token-name>"
export SONATYPE_PASSWORD="<token-password>"

./gradlew publishToSonatype
# Snapshots are immediately consumable via:
#   repositories { maven("https://central.sonatype.com/repository/maven-snapshots/") }
```

## External publish (RELEASE)

For a final release (`1.4.0`, not `1.4.0-SNAPSHOT`):

```bash
# 1. Bump version (one place — gradle.properties)
sed -i '' 's/kformikVersion=1\.4\.0-SNAPSHOT/kformikVersion=1.4.0/' gradle.properties

# 2. Re-run local verification (above)
./gradlew clean publishToMavenLocal

# 3. Publish to Sonatype staging
./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository
# This:
#  - signs every artifact
#  - uploads to OSSRH staging
#  - validates the staging repo (checksums, javadoc/sources presence, POM completeness, GPG sig)
#  - on success, drops the staging repo into the Maven Central sync queue
# Sync to repo1.maven.org takes ~10-30 minutes.

# 4. Tag and push
git tag v1.4.0
git push origin v1.4.0

# 5. Create a GitHub release
gh release create v1.4.0 \
    --title "v1.4.0" \
    --notes-file CHANGELOG.md
# (or attach release notes from CHANGELOG.md's v1.4.0 section)

# 6. Bump back to next SNAPSHOT
sed -i '' 's/kformikVersion=1\.4\.0/kformikVersion=1.5.0-SNAPSHOT/' gradle.properties
git add gradle.properties && git commit -m "Back to 1.5.0-SNAPSHOT"
git push
```

## Staging / release task graph

The build uses [`io.github.gradle-nexus.publish-plugin`](https://github.com/gradle-nexus/publish-plugin) v2.0.0 (configured in the root `build.gradle.kts`). It contributes:

- `:initializeSonatypeStagingRepository` — opens a staging repo
- `:publishAllPublicationsToSonatypeRepository` — uploads artifacts
- `:closeSonatypeStagingRepository` — runs Sonatype's validation
- `:releaseSonatypeStagingRepository` — promotes to Maven Central
- `:closeAndReleaseSonatypeStagingRepository` — the two above combined
- `:dropSonatypeStagingRepository` — abort a failed staging repo

For initial debugging, run `closeSonatypeStagingRepository` separately; the Portal deployments view at <https://central.sonatype.com/publishing> shows validation errors more clearly than the CLI output.

## Rollback

| Scenario | Recovery |
|---|---|
| Local-only mistake | Discard the change; `./gradlew clean publishToMavenLocal` republishes. |
| SNAPSHOT pushed but broken | Push a fix and republish; SNAPSHOTs overwrite previous deploys. |
| Release rejected at `closeSonatypeStagingRepository` | Sonatype keeps the staging repo open. Drop it with `:dropSonatypeStagingRepository`, fix the issue, run the publish again. |
| Release accepted but broken | **You cannot un-publish from Maven Central.** Cut a new patch release (`1.4.1`) immediately; mark the broken version yanked in the GitHub release notes. |
| Wrong coordinates / signing | Same as above — cut a new release with the corrected coordinates. |

## Version-bump rules

- **Patch** (`1.4.0` → `1.4.1`) — bug fixes, doc fixes, additional tests. No new API.
- **Minor** (`1.4.x` → `1.5.0`) — new API surface that's backward-compatible.
- **Major** (`1.x` → `2.0.0`) — breaking API changes. Provide migration notes in `docs/migration/`.

Only `kformikVersion` in `gradle.properties` needs editing — the root `build.gradle.kts` propagates it to every subproject via `subprojects { version = rootProject.version }`.

## Git tag / GitHub release process

```bash
# 1. After a successful external publish, tag the exact commit
git tag -a v1.4.0 -m "Kformik v1.4.0"
git push origin v1.4.0

# 2. Generate release notes from the CHANGELOG.md section for this version
awk '/^## \[1.4.0\]/,/^## \[/{if (/^## \[/ && !/1.4.0/) exit; print}' CHANGELOG.md > /tmp/release-notes-1.4.0.md

# 3. Create the GitHub release
gh release create v1.4.0 --title "v1.4.0" --notes-file /tmp/release-notes-1.4.0.md

# 4. Optional: attach a fat "sources + docs" zip for offline consumers
./gradlew :kformik:dokkaHtml :kformik-compose:dokkaHtml :kformik-ksp:dokkaHtml
zip -r /tmp/kformik-1.4.0-docs.zip kformik/build/dokka kformik-compose/build/dokka kformik-ksp/build/dokka
gh release upload v1.4.0 /tmp/kformik-1.4.0-docs.zip
```

## What NOT to publish

- ❌ `:examples` — JVM examples module, intentionally has no `maven-publish` plugin.
- ❌ `:sample-android-app` — application module, no API surface.
- ❌ Anything under `.private/` — local working notes, not for publication.

Only `:kformik`, `:kformik-compose`, `:kformik-ksp` are published.

## Known limitations before first real release

1. **No release workflow** — `.github/workflows/ci.yml` runs tests/assemble/`apiCheck`/`publishToMavenLocal` on push + PR, but there is no `.github/workflows/release.yml`. A release workflow would need to invoke `publishToSonatype closeAndReleaseSonatypeStagingRepository` from a runner with the `SIGNING_*`/`SONATYPE_*` secrets configured.
2. **Compose UI Robolectric tests are gated off by default** — they live in `:sample-android-app/src/robolectricTest/` and require `-PwithRobolectric=true` plus network access for the 158 MB native-runtime JAR.
3. **iOS device target (`iosArm64`)** compiles but its tests aren't run in CI — no physical device wired up. The simulator (`iosSimulatorArm64`) is.
4. **KSP support is experimental** — flat + nested `@FormValues` data classes work; `List<...>` / `Map<...>` / sealed / generic types are not yet covered. See `docs/KSP_TYPED_PATHS.md`.

## Quick reference

```bash
# Local sanity
./gradlew publishToMavenLocal

# Verify Dokka docs jars include real HTML
V=$(grep '^kformikVersion=' gradle.properties | cut -d= -f2); jar tf ~/.m2/repository/io/github/apdelrahman1911/kformik-jvm/$V/kformik-jvm-$V-javadoc.jar | head

# Generate just the docs (per-module)
./gradlew :kformik:dokkaHtml :kformik-compose:dokkaHtml :kformik-ksp:dokkaHtml

# Publish a SNAPSHOT externally (requires SONATYPE_USERNAME/PASSWORD + SIGNING_*)
./gradlew publishToSonatype

# Publish a release externally
./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository

# Drop a failed staging repo
./gradlew dropSonatypeStagingRepository
```
