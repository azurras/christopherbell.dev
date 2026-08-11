package dev.christopherbell.configuration.mongo.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class DomainCollectionManifestResourceTest {
  private static final Path SCRIPT = repositoryRoot().resolve(
      "ops/production/windows/scripts/DomainCollectionManifest.js");

  @Test
  void javascriptManifestEmbedsTheExactJavaCanonicalManifest() throws Exception {
    var script = Files.readString(SCRIPT, StandardCharsets.UTF_8);
    var prefix = "const CANONICAL_MANIFEST = String.raw`";
    var begin = script.indexOf(prefix);
    var contentStart = begin + prefix.length();
    var end = script.indexOf('`', contentStart);

    assertThat(begin).isGreaterThanOrEqualTo(0);
    assertThat(end).isGreaterThan(begin);
    var canonical = script.substring(contentStart, end)
        .replace("\r\n", "\n");

    assertThat(canonical).isEqualTo(DomainCollectionManifest.canonicalManifestText());
    assertThat(sha256(canonical)).isEqualTo(DomainCollectionManifest.DIGEST);
  }

  private static String sha256(String value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  private static Path repositoryRoot() {
    var current = Path.of("").toAbsolutePath();
    while (current != null && !Files.exists(current.resolve("settings.gradle.kts"))) {
      current = current.getParent();
    }
    if (current == null) {
      throw new IllegalStateException("Repository root could not be located.");
    }
    return current;
  }
}
