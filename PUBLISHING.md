# Publishing harness-stim

This repository is a fresh-history allowlisted cut. Maintain the public
build/release templates in the development repository's `oss/` directory and
run `scripts/oss-export.sh` there. Never copy its Git directory or push its
branches into this public repository. Internal test plans and development
scripts are excluded.

## Validation

Requires JDK 26 or newer and Maven 3.9. CI pins the official Apache Maven
3.9.16 distribution and verifies its SHA-512 checksum. The current Central
plugin bundle has been validated with this Maven version.

```sh
./scripts/build.sh -Prelease -Dgpg.skip=true
python3 scripts/verify-release.py
```

This runs all tests and builds the executable library, sources and Javadoc
JARs. The build helper uses empty Maven settings and a fresh isolated cache,
so public builds do not need registry credentials or sibling checkouts.

## Release

1. Export and validate a clean source cut, then push the public commit.
2. Set the release version and matching SCM tag in `pom.xml`; update versioned
   documentation and CI CLI filenames together.
3. Wait for public CI to pass, then publish a non-prerelease GitHub Release at
   `v<version>` on that exact public commit.
4. Release CI validates coordinates, rebuilds and tests, then signs and deploys
   with the Maven Central recipe used by ffmpeg-ffm, panama-media and jmux.
5. Verify anonymous Central downloads and signatures for the JARs and POM.

Retry a failed unpublished release without moving its tag:

```sh
gh workflow run release.yml --ref main -f release_tag=v0.1.0
```

Both release jobs check out the existing tag. Published Central artifacts are
immutable; use a new version for changes.

## Repository secrets

Use the existing `gpg-backup-audit/set-maven-central-secrets.sh` helper to set
`GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`, `MAVEN_CENTRAL_USERNAME` and
`MAVEN_CENTRAL_PASSWORD`. Only the publishing job receives these secrets.

Coordinates: `io.github.ghosthack:harness-stim:0.1.0`.
Original code is MIT licensed; the main JAR is already a standalone CLI.
