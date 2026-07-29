package dev.christopherbell.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BuildAutomationConfigurationTest {
  private static final Path REPOSITORY_ROOT = locateRepositoryRoot();

  @Test
  void artifactVersionUsesReleaseInputOrExactCommitWithoutClockInputs() throws IOException {
    var script = Files.readString(REPOSITORY_ROOT.resolve("build.gradle.kts"));

    assertThat(script).contains(
        "releaseVersion", "RELEASE_VERSION", "0.0.0-dev.", "verifyDeterministicVersion");
    assertThat(script).doesNotContain("LocalDate", "BUILD_NUMBER");
  }

  @Test
  void sensorPreparationUsesVerifiedOfflineCacheAndBoundedDownload() throws IOException {
    var script = Files.readString(REPOSITORY_ROOT.resolve("website/build.gradle.kts"));

    assertThat(script).contains(
        "gradleUserHomeDir",
        "isOffline",
        "connectTimeout",
        "readTimeout",
        "verifySensorArchiveResolution");
    assertThat(script).doesNotContain(".toURL().openStream()");
  }

  private static Path locateRepositoryRoot() {
    var current = Path.of("").toAbsolutePath().normalize();
    if (Files.isDirectory(current.resolve(".github"))) {
      return current;
    }
    var parent = current.getParent();
    if (parent != null && Files.isDirectory(parent.resolve(".github"))) {
      return parent;
    }
    throw new IllegalStateException("Cannot locate repository root from " + current);
  }
}
