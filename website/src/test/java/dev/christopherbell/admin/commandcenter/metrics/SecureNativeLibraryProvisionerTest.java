package dev.christopherbell.admin.commandcenter.metrics;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class SecureNativeLibraryProvisionerTest {
  @TempDir Path tempDir;

  @Test
  void refusesPreexistingVersionDirectoryWithoutLoadingItsFiles() throws Exception {
    Path existing = Files.createDirectories(tempDir.resolve("jlibre-1.0.6-fixed"));
    Files.writeString(existing.resolve("LibreHardwareMonitorLib.dll"), "malicious", UTF_8);
    var provisioner = provisioner("trusted", hash("trusted"), path -> {}, "fixed");

    assertThatThrownBy(provisioner::provision).isInstanceOf(SecurityException.class);
    assertThat(Files.readString(existing.resolve("LibreHardwareMonitorLib.dll"), UTF_8))
        .isEqualTo("malicious");
  }

  @Test
  void checksumMismatchFailsClosedAndRemovesCreatedVersionDirectory() {
    var provisioner = provisioner("tampered", hash("trusted"), path -> {}, "mismatch");

    assertThatThrownBy(provisioner::provision).isInstanceOf(SecurityException.class);
    assertThat(tempDir.resolve("jlibre-1.0.6-mismatch")).doesNotExist();
  }

  @Test
  void aclHardeningFailureFailsClosedBeforeAnyLibraryIsWritten() {
    var provisioner = provisioner("trusted", hash("trusted"), path -> {
      throw new SecurityException("ACL rejected");
    }, "acl-failure");

    assertThatThrownBy(provisioner::provision).isInstanceOf(SecurityException.class);
    assertThat(tempDir.resolve("jlibre-1.0.6-acl-failure/LibreHardwareMonitorLib.dll"))
        .doesNotExist();
  }

  @Test
  void verifiedCreateNewExtractionReturnsOnlyFreshLibraryAndCleansItUp() throws Exception {
    var provisioner = provisioner("trusted", hash("trusted"), path -> {}, "verified");

    var libraries = provisioner.provision();

    assertThat(Files.readString(libraries.libreHardwareMonitor(), UTF_8)).isEqualTo("trusted");
    Path directory = libraries.directory();
    libraries.close();
    assertThat(directory).doesNotExist();
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void productionAclAllowsOnlySystemAdministratorsAndOwner() throws Exception {
    var libraries = new SecureNativeLibraryProvisioner(tempDir.resolve("secure")).provision();
    try {
      var acl = Files.getFileAttributeView(
          libraries.libreHardwareMonitor(), java.nio.file.attribute.AclFileAttributeView.class).getAcl();
      assertThat(acl).allSatisfy(entry -> {
        if (entry.type() == java.nio.file.attribute.AclEntryType.ALLOW) {
          String principal = entry.principal().getName().toLowerCase(java.util.Locale.ROOT);
          assertThat(principal).doesNotEndWith("\\users");
          assertThat(principal).doesNotContain("everyone", "authenticated users");
        }
      });
    } finally {
      libraries.close();
    }
  }

  private SecureNativeLibraryProvisioner provisioner(
      String bytes,
      String expectedHash,
      SecureNativeLibraryProvisioner.AclPolicy acl,
      String nonce) {
    return new SecureNativeLibraryProvisioner(
        tempDir,
        List.of(new SecureNativeLibraryProvisioner.ResourceSpec(
            "LibreHardwareMonitorLib.dll", expectedHash,
            () -> new ByteArrayInputStream(bytes.getBytes(UTF_8)))),
        acl,
        () -> nonce);
  }

  private static String hash(String value) {
    try {
      return java.util.HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)));
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }
}
