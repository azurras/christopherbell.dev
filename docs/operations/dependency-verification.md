# Dependency verification

The Gradle wrapper, repository dependencies, plugins, and GitHub Actions are pinned to reviewed immutable bytes.

Treat dependency-verification metadata generation as discovery, not as evidence that the
downloaded bytes are trustworthy. Before invoking Gradle, inspect the build-file diff and
confirm every directly changed coordinate against the expected publisher and release.

Perform discovery only in a disposable checkout and process environment with an empty isolated
`GRADLE_USER_HOME`, no credentials, tokens, signing keys, or production access, and no write
access to the authoritative checkout. Gradle can configure newly resolved plugins even when task
actions are disabled, so this isolation is required. Generate an untrusted preview without
executing the normal build task graph:

`./gradlew.bat --write-verification-metadata sha256 help --dry-run --no-daemon`

Review every new or changed entry in `gradle/verification-metadata.dryrun.xml` against an
independent upstream checksum, signature, or release provenance. A checksum obtained from the
same artifact repository is not independent evidence when repository compromise is the threat.
Copy only reviewed values into `gradle/verification-metadata.xml`, and review that diff before
running any normal Gradle build.

Prove the reviewed result from a second empty Gradle home:

`./gradlew.bat build --dependency-verification=strict --no-daemon`

If strict verification reports a task-time artifact that was absent from the preview, do not
combine metadata writing with `build`. Return to the disposable environment, discover the
missing component without secrets or production access, verify it independently, and add only
the reviewed value before retrying the strict build.

Workflow action updates must retain the intended release in a comment and pin the `uses:` value to the official upstream commit SHA. Annotated tags must be peeled to their commit.
