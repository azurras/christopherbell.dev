package dev.christopherbell.configuration.persistence.migration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
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
      "writerLockPath", "writerLockDigest", "evidenceDigest", "signature");

  private FinalizeEvidenceLoader() {}

  static FrozenSourceEvidence load(Path path, Path hmacKeyPath) {
    if (!protectedRegularFile(path) || !protectedRegularFile(hmacKeyPath)) {
      throw invalid();
    }
    byte[] hmacKey;
    try {
      hmacKey = Files.readAllBytes(hmacKeyPath);
    } catch (IOException failure) {
      throw invalid();
    }
    if (hmacKey.length < 32) {
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
          required(properties, "targetRole"), required(properties, "writerLockPath"),
          required(properties, "writerLockDigest"),
          required(properties, "evidenceDigest"));
      requireWriterLock(evidence);
      var signature = HexFormat.of().parseHex(required(properties, "signature"));
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
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

  static void requireWriterLock(FrozenSourceEvidence evidence) {
    try {
      var writerLock = Path.of(evidence.writerLockPath()).toAbsolutePath().normalize();
      if (!protectedRegularFile(writerLock)) {
        throw invalid();
      }
      var writerLockText = Files.readString(writerLock, StandardCharsets.UTF_8);
      if (!evidence.writerLockDigest().equals(CanonicalMigrationHasher.sha256(writerLockText))
          || !writerLockText.lines().toList().contains("lockToken=" + evidence.lockToken())
          || !writerLockText.lines().toList().contains("release=" + evidence.release())) {
        throw invalid();
      }
    } catch (RuntimeException | IOException failure) {
      throw invalid();
    }
  }

  private static boolean protectedRegularFile(Path path) {
    if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(path)) {
      return false;
    }
    try {
      var posix = Files.getFileAttributeView(
          path, java.nio.file.attribute.PosixFileAttributeView.class,
          LinkOption.NOFOLLOW_LINKS);
      if (posix != null) {
        var permissions = posix.readAttributes().permissions();
        return java.util.Collections.disjoint(permissions, Set.of(
            java.nio.file.attribute.PosixFilePermission.GROUP_WRITE,
            java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE));
      }
      var owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).getName();
      var acl = Files.getFileAttributeView(
          path, java.nio.file.attribute.AclFileAttributeView.class,
          LinkOption.NOFOLLOW_LINKS);
      if (acl == null) {
        return false;
      }
      var write = Set.of(
          java.nio.file.attribute.AclEntryPermission.WRITE_DATA,
          java.nio.file.attribute.AclEntryPermission.APPEND_DATA,
          java.nio.file.attribute.AclEntryPermission.WRITE_ATTRIBUTES,
          java.nio.file.attribute.AclEntryPermission.WRITE_NAMED_ATTRS,
          java.nio.file.attribute.AclEntryPermission.WRITE_ACL,
          java.nio.file.attribute.AclEntryPermission.WRITE_OWNER,
          java.nio.file.attribute.AclEntryPermission.DELETE);
      return acl.getAcl().stream().filter(entry -> entry.type()
              == java.nio.file.attribute.AclEntryType.ALLOW)
          .filter(entry -> !java.util.Collections.disjoint(entry.permissions(), write))
          .allMatch(entry -> trustedPrincipal(entry.principal().getName(), owner));
    } catch (IOException failure) {
      return false;
    }
  }

  private static boolean trustedPrincipal(String principal, String owner) {
    var normalized = principal.toLowerCase(java.util.Locale.ROOT);
    return principal.equalsIgnoreCase(owner)
        || normalized.endsWith("\\system")
        || normalized.endsWith("\\administrators");
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
