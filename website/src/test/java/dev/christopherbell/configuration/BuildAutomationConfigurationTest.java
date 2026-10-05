package dev.christopherbell.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
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
  void packagedBuildInfoCarriesTheCommitDerivedReleaseVersion() throws IOException {
    var buildInfo = new Properties();
    try (var packagedBuildInfo = getClass().getResourceAsStream("/META-INF/build-info.properties")) {
      assertThat(packagedBuildInfo).isNotNull();
      buildInfo.load(packagedBuildInfo);
    }

    // Production Watch reads the live commit from /actuator/info build.version.
    assertThat(buildInfo.getProperty("build.version"))
        .matches("0\\.0\\.0-dev\\.[0-9a-f]{40}|[0-9A-Za-z][0-9A-Za-z._+-]{0,127}")
        .isNotEqualTo("unspecified");
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

  @Test
  void productionPackagingExemptsOnlyTheProtectedWindowsDeploymentContext() throws IOException {
    var build = Files.readString(REPOSITORY_ROOT.resolve("website/build.gradle.kts"));
    var deploy =
        Files.readString(
            REPOSITORY_ROOT.resolve(
                "ops/production/windows/modules/Production.Deploy.psm1"));

    assertThat(build)
        .contains(
            "CHRISTOPHERBELL_PRODUCTION_DEPLOYMENT",
            "c:\\\\programdata\\\\christopherbell.dev\\\\gradle-home",
            "verifyProductionDeploymentBuildContext");
    assertThat(deploy).contains("CHRISTOPHERBELL_PRODUCTION_DEPLOYMENT = '1'");
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
