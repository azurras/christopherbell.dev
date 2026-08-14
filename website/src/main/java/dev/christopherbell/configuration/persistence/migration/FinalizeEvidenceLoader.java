package dev.christopherbell.configuration.persistence.migration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Loads independently persisted finalization authority and verifies its HMAC before DB I/O. */
final class FinalizeEvidenceLoader {
  private static final Set<String> KEYS = Set.of(
      "release", "catalogDigest", "sourceDatabase", "targetDatabase", "sourceDigest",
      "backupDigest", "lockToken", "sourceUri", "targetJdbcUrl", "targetRole",
      "writerLockDigest", "evidenceDigest", "signature");

  private FinalizeEvidenceLoader() {}

  static FrozenSourceEvidence load(Path path, String hmacKey) {
    if (path == null || hmacKey == null || hmacKey.length() < 32
        || !Files.isRegularFile(path)) {
      throw invalid();
    }
    var properties = new Properties();
    try (InputStream input = Files.newInputStream(path)) {
      properties.load(input);
    } catch (IOException failure) {
      throw invalid();
    }
    if (!properties.stringPropertyNames().equals(KEYS)) {
      throw invalid();
    }
    try {
      var evidence = new FrozenSourceEvidence(
          required(properties, "release"), required(properties, "catalogDigest"),
          required(properties, "sourceDatabase"), required(properties, "targetDatabase"),
          required(properties, "sourceDigest"), required(properties, "backupDigest"),
          UUID.fromString(required(properties, "lockToken")),
          required(properties, "sourceUri"), required(properties, "targetJdbcUrl"),
          required(properties, "targetRole"), required(properties, "writerLockDigest"),
          required(properties, "evidenceDigest"));
      var signature = HexFormat.of().parseHex(required(properties, "signature"));
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(hmacKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      var expected = mac.doFinal(evidence.evidenceDigest().getBytes(StandardCharsets.US_ASCII));
      if (!evidence.evidenceDigest().equals(evidence.reconstructedDigest())
          || !MessageDigest.isEqual(expected, signature)) {
        throw invalid();
      }
      return evidence;
    } catch (RuntimeException | java.security.GeneralSecurityException failure) {
      throw invalid();
    }
  }

  private static String required(Properties properties, String key) {
    var value = properties.getProperty(key);
    if (value == null || value.isBlank()) {
      throw invalid();
    }
    return value;
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("PostgreSQL migration finalization evidence is invalid.");
  }
}
