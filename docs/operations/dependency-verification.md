# Dependency verification

The Gradle wrapper, repository dependencies, plugins, and GitHub Actions are pinned to reviewed immutable bytes.

To update Gradle verification metadata, use an empty isolated `GRADLE_USER_HOME` and run:

`./gradlew.bat --write-verification-metadata sha256 --refresh-dependencies build --no-daemon`

Review every new or changed component against its expected repository and upstream release, then prove the result from a second empty Gradle home:

`./gradlew.bat build --dependency-verification=strict --no-daemon`

Workflow action updates must retain the intended release in a comment and pin the `uses:` value to the official upstream commit SHA. Annotated tags must be peeled to their commit.
