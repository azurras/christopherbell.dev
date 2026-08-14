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
  private static final String EVIDENCE_FILE = "finalize.properties";
  private static final String KEY_FILE = "authority.key";
  private static final String WRITER_LOCK_FILE = "writer.lock";
  private static final Set<String> KEYS = Set.of(
      "release", "catalogDigest", "sourceDatabase", "targetDatabase", "sourceDigest",
      "backupDigest", "lockToken", "sourceUri", "targetJdbcUrl", "targetRole",
      "writerLockPath", "writerLockDigest", "evidenceDigest", "signature");

  private FinalizeEvidenceLoader() {}

  static FrozenSourceEvidence loadProduction() {
    var root = productionRoot();
    if (!protectedDirectory(root.getParent(), true)
        || !protectedDirectory(root, true)) {
      throw invalid();
    }
    return load(root, root.resolve(EVIDENCE_FILE), root.resolve(KEY_FILE), true);
  }

  static FrozenSourceEvidence loadForTest(
      Path authorityRoot, Path path, Path hmacKeyPath) {
    if (!protectedDirectory(authorityRoot, false)) {
      throw invalid();
    }
    return load(authorityRoot, path, hmacKeyPath, false);
  }

  static Path productionRoot() {
    return Path.of(isWindows()
        ? "C:\\ProgramData\\christopherbell.dev\\postgresql-migration-authority"
        : "/etc/christopherbell.dev/postgresql-migration-authority")
        .toAbsolutePath().normalize();
  }

  static void requireTrustedProductionNodeForTest(Path path, boolean directory) {
    if (!(directory ? protectedDirectory(path, true) : protectedRegularFile(path, true))) {
      throw invalid();
    }
  }

  private static FrozenSourceEvidence load(
      Path authorityRoot, Path path, Path hmacKeyPath, boolean production) {
    var normalizedRoot = authorityRoot.toAbsolutePath().normalize();
    var normalizedEvidence = path.toAbsolutePath().normalize();
    var normalizedKey = hmacKeyPath.toAbsolutePath().normalize();
    if (!normalizedEvidence.equals(normalizedRoot.resolve(EVIDENCE_FILE))
        || !normalizedKey.equals(normalizedRoot.resolve(KEY_FILE))
        || !protectedRegularFile(normalizedEvidence, production)
        || !protectedRegularFile(normalizedKey, production)) {
      throw invalid();
    }
    byte[] hmacKey;
    try {
      hmacKey = Files.readAllBytes(normalizedKey);
    } catch (IOException failure) {
      throw invalid();
    }
    if (hmacKey.length < 32) {
      throw invalid();
    }
    var properties = new Properties();
    try (InputStream input = Files.newInputStream(normalizedEvidence)) {
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
      var expectedWriterLock = normalizedRoot.resolve(WRITER_LOCK_FILE);
      if (!Path.of(evidence.writerLockPath()).toAbsolutePath().normalize()
          .equals(expectedWriterLock)) {
        throw invalid();
      }
      requireWriterLock(evidence, normalizedRoot, production);
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
    var writerLock = Path.of(evidence.writerLockPath()).toAbsolutePath().normalize();
    var production = writerLock.equals(productionRoot().resolve(WRITER_LOCK_FILE));
    requireWriterLock(evidence, writerLock.getParent(), production);
  }

  private static void requireWriterLock(
      FrozenSourceEvidence evidence, Path authorityRoot, boolean production) {
    try {
      var writerLock = Path.of(evidence.writerLockPath()).toAbsolutePath().normalize();
      if (!writerLock.equals(authorityRoot.resolve(WRITER_LOCK_FILE))
          || !protectedRegularFile(writerLock, production)) {
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

  private static boolean protectedRegularFile(Path path, boolean production) {
    if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        || reparsePoint(path)) {
      return false;
    }
    return protectedAttributes(path, production);
  }

  private static boolean protectedDirectory(Path path, boolean production) {
    if (path == null || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
        || reparsePoint(path)) {
      return false;
    }
    return protectedAttributes(path, production);
  }

  private static boolean protectedAttributes(Path path, boolean production) {
    try {
      var posix = Files.getFileAttributeView(
          path, java.nio.file.attribute.PosixFileAttributeView.class,
          LinkOption.NOFOLLOW_LINKS);
      if (posix != null) {
        var permissions = posix.readAttributes().permissions();
        return (!production || "root".equalsIgnoreCase(
                Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).getName()))
            && java.util.Collections.disjoint(permissions, Set.of(
            java.nio.file.attribute.PosixFilePermission.GROUP_WRITE,
            java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE));
      }
      var owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).getName();
      if (production && !trustedProductionPrincipal(owner)) {
        return false;
      }
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
          .allMatch(entry -> production
              ? trustedProductionWritePrincipal(entry.principal().getName())
              : trustedTestPrincipal(entry.principal().getName(), owner));
    } catch (IOException failure) {
      return false;
    }
  }

  private static boolean trustedTestPrincipal(String principal, String owner) {
    var normalized = principal.toLowerCase(java.util.Locale.ROOT);
    return principal.equalsIgnoreCase(owner)
        || normalized.endsWith("\\system")
        || normalized.endsWith("\\administrators");
  }

  private static boolean trustedProductionPrincipal(String principal) {
    var normalized = principal.toLowerCase(java.util.Locale.ROOT);
    return normalized.equals("system")
        || normalized.equals("nt authority\\system")
        || normalized.equals("nt service\\christopherbelldev");
  }

  private static boolean trustedProductionWritePrincipal(String principal) {
    var normalized = principal.toLowerCase(java.util.Locale.ROOT);
    return trustedProductionPrincipal(principal)
        || normalized.equals("builtin\\administrators");
  }

  private static boolean reparsePoint(Path path) {
    if (Files.isSymbolicLink(path)) {
      return true;
    }
    if (!isWindows()) {
      return false;
    }
    try {
      return Files.readAttributes(
          path, java.nio.file.attribute.DosFileAttributes.class,
          LinkOption.NOFOLLOW_LINKS).isOther();
    } catch (IOException failure) {
      return true;
    }
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
        .contains("win");
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
