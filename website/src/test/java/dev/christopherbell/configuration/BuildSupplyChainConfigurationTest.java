package dev.christopherbell.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class BuildSupplyChainConfigurationTest {
  private static final String GRADLE_DISTRIBUTION_SHA256 =
      "9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14";
  private static final Path REPOSITORY_ROOT = locateRepositoryRoot();

  @Test
  void wrapperUsesReviewedDistributionChecksum() throws IOException {
    assertThat(wrapperProperties().getProperty("distributionSha256Sum"))
        .isEqualTo(GRADLE_DISTRIBUTION_SHA256);
  }

  @Test
  void dependencyVerificationMetadataCoversResolvedComponents() throws IOException {
    var metadata = REPOSITORY_ROOT.resolve("gradle/verification-metadata.xml");
    assertThat(metadata).isRegularFile().isNotEmptyFile();
    assertThat(Files.readString(metadata)).contains("<component ");
  }

  @Test
  void dependencyMetadataUpdatesDoNotExecuteUnreviewedArtifacts() throws IOException {
    var guide = Files.readString(
        REPOSITORY_ROOT.resolve("docs/operations/dependency-verification.md"));

    assertThat(guide)
        .doesNotContain("--refresh-dependencies build")
        .contains("help --dry-run")
        .contains("disposable")
        .contains("no credentials, tokens, signing keys, or production access")
        .contains("review that diff before")
        .contains("running any normal Gradle build");
  }

  private static Properties wrapperProperties() throws IOException {
    var properties = new Properties();
    try (InputStream input = Files.newInputStream(
        REPOSITORY_ROOT.resolve("gradle/wrapper/gradle-wrapper.properties"))) {
      properties.load(input);
    }
    return properties;
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
