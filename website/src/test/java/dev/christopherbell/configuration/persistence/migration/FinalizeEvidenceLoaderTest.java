package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class FinalizeEvidenceLoaderTest {
  private static final String KEY = "independent-test-authority-key-00000001";

  @Test
  void rejectsAnAuthenticWriterLeaseThatIsNotFrozenOrHasExpired(@TempDir Path directory)
      throws Exception {
    protect(directory);
    var keyPath = directory.resolve("authority.key");
    Files.writeString(keyPath, KEY, StandardCharsets.UTF_8);
    protect(keyPath);
    var lockPath = directory.resolve("writer.lock");
    var active = "lockToken=00000000-0000-0000-0000-000000000016\n"
        + "release=release-6\nstate=active\nleaseExpiresAt=2026-08-15T00:00:00Z\n";
    Files.writeString(lockPath, active, StandardCharsets.UTF_8);
    protect(lockPath);
    var evidence = evidence(lockPath, active);
    writeEvidence(directory.resolve("finalize.properties"), evidence);

    var clock = Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC);
    assertThatThrownBy(() -> FinalizeEvidenceLoader.loadForTest(
        directory, directory.resolve("finalize.properties"), keyPath, clock))
        .isInstanceOf(IllegalArgumentException.class);

    var expired = active.replace("state=active", "state=frozen")
        .replace("2026-08-15T00:00:00Z", "2026-08-13T00:00:00Z");
    Files.writeString(lockPath, expired, StandardCharsets.UTF_8);
    protect(lockPath);
    writeEvidence(directory.resolve("finalize.properties"), evidence(lockPath, expired));
    assertThatThrownBy(() -> FinalizeEvidenceLoader.loadForTest(
        directory, directory.resolve("finalize.properties"), keyPath, clock))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void verifiesPersistedAuthorityAndRejectsTampering(@TempDir Path directory) throws Exception {
    protect(directory);
    var keyPath = directory.resolve("authority.key");
    Files.writeString(keyPath, KEY, StandardCharsets.UTF_8);
    protect(keyPath);
    var lockPath = directory.resolve("writer.lock");
    var lockText = "lockToken=00000000-0000-0000-0000-000000000016\nrelease=release-6\n"
        + "state=frozen\nleaseExpiresAt=2999-01-01T00:00:00Z\n";
    Files.writeString(lockPath, lockText, StandardCharsets.UTF_8);
    protect(lockPath);
    var evidence = evidence(lockPath, lockText);
    var properties = properties(evidence);
    properties.setProperty("signature", signature(evidence.evidenceDigest()));
    var path = directory.resolve("finalize.properties");
    try (var output = Files.newOutputStream(path)) {
      properties.store(output, null);
    }
    protect(path);

    var loaded = FinalizeEvidenceLoader.loadForTest(directory, path, keyPath);
    assertThat(loaded).isEqualTo(evidence);

    Files.writeString(
        lockPath, lockText.replace("state=frozen", "state=unfrozen"), StandardCharsets.UTF_8);
    protect(lockPath);
    assertThatThrownBy(() -> FinalizeEvidenceLoader.requireWriterLock(loaded))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("PostgreSQL migration finalization evidence is invalid.");
    Files.writeString(lockPath, lockText, StandardCharsets.UTF_8);
    protect(lockPath);

    properties.setProperty("sourceDigest", "f".repeat(64));
    try (var output = Files.newOutputStream(path)) {
      properties.store(output, null);
    }
    assertThatThrownBy(() -> FinalizeEvidenceLoader.loadForTest(directory, path, keyPath))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("PostgreSQL migration finalization evidence is invalid.")
        .hasMessageNotContaining("f".repeat(64));
  }

  @Test
  void productionAuthorityRootIsFixedAndSelfMintedOwnerOnlyFilesAreRejected(
      @TempDir Path directory) throws Exception {
    protect(directory);
    var selfMinted = directory.resolve("finalize.properties");
    Files.writeString(selfMinted, "self-minted", StandardCharsets.UTF_8);
    protect(selfMinted);

    assertThat(FinalizeEvidenceLoader.productionRoot().toString())
        .endsWith(System.getProperty("os.name").toLowerCase().contains("win")
            ? "ProgramData\\christopherbell.dev\\postgresql-migration-authority"
            : "/etc/christopherbell.dev/postgresql-migration-authority");
    assertThatThrownBy(() ->
        FinalizeEvidenceLoader.requireTrustedProductionNodeForTest(selfMinted, false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAReparseOrSymlinkInAnAuthorityAncestor(@TempDir Path directory) throws Exception {
    var anchor = directory.resolve("trusted-anchor");
    var destination = directory.resolve("redirect-destination");
    Files.createDirectories(anchor);
    Files.createDirectories(destination.resolve("authority"));
    protect(anchor);
    protect(destination);
    protect(destination.resolve("authority"));
    var redirected = anchor.resolve("redirected");
    try {
      Files.createSymbolicLink(redirected, destination);
    } catch (java.nio.file.FileSystemException | UnsupportedOperationException failure) {
      org.junit.jupiter.api.Assumptions.abort("symbolic links are unavailable");
    }

    assertThatThrownBy(() -> FinalizeEvidenceLoader.requireProtectedPathForTest(
        anchor, redirected.resolve("authority")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAnAuthorityAncestorWithUntrustedDeleteChildPermission(@TempDir Path directory)
      throws Exception {
    var anchor = directory.resolve("trusted-anchor");
    var parent = anchor.resolve("parent");
    var root = parent.resolve("authority");
    Files.createDirectories(root);
    protect(anchor);
    protect(parent);
    protect(root);
    allowUntrustedDeleteChild(parent);

    assertThatThrownBy(() ->
        FinalizeEvidenceLoader.requireProtectedPathForTest(anchor, root))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAnAuthorityRootWithUntrustedDeletePermission(@TempDir Path directory)
      throws Exception {
    var anchor = directory.resolve("trusted-anchor");
    var root = anchor.resolve("authority");
    Files.createDirectories(root);
    protect(anchor);
    protect(root);
    allowUntrustedDelete(root);

    assertThatThrownBy(() ->
        FinalizeEvidenceLoader.requireProtectedPathForTest(anchor, root))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAReparseOrSymlinkAuthorityRoot(@TempDir Path directory) throws Exception {
    var anchor = directory.resolve("trusted-anchor");
    var destination = directory.resolve("redirect-destination");
    Files.createDirectories(anchor);
    Files.createDirectories(destination);
    protect(anchor);
    protect(destination);
    var root = anchor.resolve("authority");
    try {
      Files.createSymbolicLink(root, destination);
    } catch (java.nio.file.FileSystemException | UnsupportedOperationException failure) {
      org.junit.jupiter.api.Assumptions.abort("symbolic links are unavailable");
    }

    assertThatThrownBy(() -> FinalizeEvidenceLoader.requireProtectedPathForTest(anchor, root))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void actualProgramDataAnchorAcceptsOrdinarySiblingCreationRights() {
    org.assertj.core.api.Assertions.assertThatCode(() ->
        FinalizeEvidenceLoader.requireTrustedProductionAnchorForTest(
            Path.of("C:\\ProgramData")))
        .doesNotThrowAnyException();
  }

  private static FrozenSourceEvidence evidence(Path lockPath, String lockText) {
    var unsigned = new FrozenSourceEvidence(
        "release-6", "a".repeat(64), "test", "test", "b".repeat(64), "c".repeat(64),
        UUID.fromString("00000000-0000-0000-0000-000000000016"),
        "mongodb://127.0.0.1:57018/test", "jdbc:postgresql://127.0.0.1:55432/test",
        "christopherbell_test", lockPath.toAbsolutePath().normalize().toString(),
        CanonicalMigrationHasher.sha256(lockText), "e".repeat(64));
    return new FrozenSourceEvidence(
        unsigned.release(), unsigned.catalogDigest(), unsigned.sourceDatabase(),
        unsigned.targetDatabase(), unsigned.sourceDigest(), unsigned.backupDigest(),
        unsigned.lockToken(), unsigned.sourceUri(), unsigned.targetJdbcUrl(), unsigned.targetRole(),
        unsigned.writerLockPath(), unsigned.writerLockDigest(), unsigned.reconstructedDigest());
  }

  private static Properties properties(FrozenSourceEvidence evidence) {
    var result = new Properties();
    result.setProperty("release", evidence.release());
    result.setProperty("catalogDigest", evidence.catalogDigest());
    result.setProperty("sourceDatabase", evidence.sourceDatabase());
    result.setProperty("targetDatabase", evidence.targetDatabase());
    result.setProperty("sourceDigest", evidence.sourceDigest());
    result.setProperty("backupDigest", evidence.backupDigest());
    result.setProperty("lockToken", evidence.lockToken().toString());
    result.setProperty("sourceUri", evidence.sourceUri());
    result.setProperty("targetJdbcUrl", evidence.targetJdbcUrl());
    result.setProperty("targetRole", evidence.targetRole());
    result.setProperty("writerLockPath", evidence.writerLockPath());
    result.setProperty("writerLockDigest", evidence.writerLockDigest());
    result.setProperty("evidenceDigest", evidence.evidenceDigest());
    return result;
  }

  private static String signature(String evidenceDigest) throws Exception {
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(evidenceDigest.getBytes(StandardCharsets.US_ASCII)));
  }

  private static void writeEvidence(Path path, FrozenSourceEvidence evidence) throws Exception {
    var values = properties(evidence);
    values.setProperty("signature", signature(evidence.evidenceDigest()));
    try (var output = Files.newOutputStream(path)) {
      values.store(output, null);
    }
    protect(path);
  }

  private static void protect(Path path) throws Exception {
    var posix = Files.getFileAttributeView(
        path, java.nio.file.attribute.PosixFileAttributeView.class);
    if (posix != null) {
      posix.setPermissions(java.util.Set.of(
          java.nio.file.attribute.PosixFilePermission.OWNER_READ,
          java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
      return;
    }
    var owner = Files.getOwner(path);
    var acl = Files.getFileAttributeView(path, java.nio.file.attribute.AclFileAttributeView.class);
    acl.setAcl(List.of(java.nio.file.attribute.AclEntry.newBuilder()
        .setType(java.nio.file.attribute.AclEntryType.ALLOW)
        .setPrincipal(owner)
        .setPermissions(java.util.EnumSet.allOf(
            java.nio.file.attribute.AclEntryPermission.class))
        .build()));
  }

  private static void allowUntrustedDeleteChild(Path path) throws Exception {
    var posix = Files.getFileAttributeView(
        path, java.nio.file.attribute.PosixFileAttributeView.class);
    if (posix != null) {
      var permissions = java.util.EnumSet.copyOf(posix.readAttributes().permissions());
      permissions.add(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE);
      posix.setPermissions(permissions);
      return;
    }
    var lookup = path.getFileSystem().getUserPrincipalLookupService();
    var everyone = lookup.lookupPrincipalByName("Everyone");
    var acl = Files.getFileAttributeView(path, java.nio.file.attribute.AclFileAttributeView.class);
    var entries = new java.util.ArrayList<>(acl.getAcl());
    entries.add(java.nio.file.attribute.AclEntry.newBuilder()
        .setType(java.nio.file.attribute.AclEntryType.ALLOW)
        .setPrincipal(everyone)
        .setPermissions(java.nio.file.attribute.AclEntryPermission.DELETE_CHILD)
        .build());
    acl.setAcl(entries);
  }

  private static void allowUntrustedDelete(Path path) throws Exception {
    var posix = Files.getFileAttributeView(
        path, java.nio.file.attribute.PosixFileAttributeView.class);
    if (posix != null) {
      var permissions = java.util.EnumSet.copyOf(posix.readAttributes().permissions());
      permissions.add(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE);
      posix.setPermissions(permissions);
      return;
    }
    var lookup = path.getFileSystem().getUserPrincipalLookupService();
    var everyone = lookup.lookupPrincipalByName("Everyone");
    var acl = Files.getFileAttributeView(path, java.nio.file.attribute.AclFileAttributeView.class);
    var entries = new java.util.ArrayList<>(acl.getAcl());
    entries.add(java.nio.file.attribute.AclEntry.newBuilder()
        .setType(java.nio.file.attribute.AclEntryType.ALLOW)
        .setPrincipal(everyone)
        .setPermissions(java.nio.file.attribute.AclEntryPermission.DELETE)
        .build());
    acl.setAcl(entries);
  }
}
