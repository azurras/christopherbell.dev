package dev.christopherbell.configuration.mongo.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DomainCollectionReleaseMetadataTest {
  private static final String RELEASE = "a".repeat(40);

  @Test
  void targetReleaseRequiresRecurringDomainGate(@TempDir Path directory) throws Exception {
    var metadata = directory.resolve("release.json");
    Files.writeString(metadata, """
        {"sha":"%s","source":"origin/main","builtAt":"2026-08-11T00:00:00Z",\
        "musicSchema":"TARGET","domainSchema":"TARGET"}
        """.formatted(RELEASE));

    assertThat(new DomainCollectionReleaseMetadata(
        new ObjectMapper(), RELEASE, metadata).requiresTarget()).isTrue();
  }

  @Test
  void managedReleaseRejectsMissingOrMismatchedMetadata(@TempDir Path directory) throws Exception {
    var missing = directory.resolve("missing.json");
    assertThatThrownBy(() -> new DomainCollectionReleaseMetadata(
        new ObjectMapper(), RELEASE, missing).requiresTarget())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining(RELEASE);

    var mismatch = directory.resolve("release.json");
    Files.writeString(mismatch, """
        {"sha":"%s","source":"origin/main","builtAt":"2026-08-11T00:00:00Z",\
        "musicSchema":"TARGET","domainSchema":"TARGET"}
        """.formatted("b".repeat(40)));
    assertThatThrownBy(() -> new DomainCollectionReleaseMetadata(
        new ObjectMapper(), RELEASE, mismatch).requiresTarget())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining(RELEASE)
        .hasMessageNotContaining("b".repeat(40));
  }

  @Test
  void unmanagedLocalRuntimeDoesNotRequireReleaseMetadata(@TempDir Path directory) {
    assertThat(new DomainCollectionReleaseMetadata(
        new ObjectMapper(), "", directory.resolve("missing.json")).requiresTarget()).isFalse();
  }
}
