package dev.christopherbell.configuration.mongo.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Reads the active release's domain-schema direction without trusting ambient defaults. */
@Component
public final class DomainCollectionReleaseMetadata {
  private static final Pattern RELEASE = Pattern.compile("[0-9a-f]{40}");
  private static final Set<String> FIELDS = Set.of(
      "sha", "source", "builtAt", "musicSchema", "domainSchema");
  private static final String INVALID = "Active release domain metadata is invalid.";

  private final ObjectMapper mapper;
  private final String expectedRelease;
  private final Path metadata;

  @Autowired
  public DomainCollectionReleaseMetadata(
      ObjectMapper mapper,
      @Value("${GIT_COMMIT:}") String expectedRelease) {
    this(mapper, expectedRelease, Path.of("release.json").toAbsolutePath().normalize());
  }

  DomainCollectionReleaseMetadata(
      ObjectMapper mapper, String expectedRelease, Path metadata) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.expectedRelease = Objects.requireNonNull(expectedRelease, "expectedRelease");
    this.metadata = Objects.requireNonNull(metadata, "metadata");
  }

  /** Returns false only for an explicitly unmanaged local runtime or a LEGACY release. */
  public boolean requiresTarget() {
    if (expectedRelease.isBlank()) {
      return false;
    }
    if (!RELEASE.matcher(expectedRelease).matches()) {
      throw new IllegalStateException(INVALID);
    }
    try {
      var root = mapper.readTree(metadata.toFile());
      var names = new java.util.HashSet<String>();
      root.fieldNames().forEachRemaining(names::add);
      if (!root.isObject() || !names.equals(FIELDS)
          || !expectedRelease.equals(root.path("sha").textValue())) {
        throw new IllegalStateException(INVALID);
      }
      var schema = root.path("domainSchema").textValue();
      if (!"LEGACY".equals(schema) && !"TARGET".equals(schema)) {
        throw new IllegalStateException(INVALID);
      }
      return "TARGET".equals(schema);
    } catch (IOException | RuntimeException failure) {
      if (failure instanceof IllegalStateException state && INVALID.equals(state.getMessage())) {
        throw state;
      }
      throw new IllegalStateException(INVALID, failure);
    }
  }
}
