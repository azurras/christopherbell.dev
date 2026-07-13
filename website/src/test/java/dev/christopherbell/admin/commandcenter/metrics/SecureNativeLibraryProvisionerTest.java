package dev.christopherbell.admin.commandcenter.metrics;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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
  void verifiedCreateNewExtractionReturnsPinnedLibraryAndScriptAndCleansThemUp() throws Exception {
    var provisioner = provisionerWithLibraryAndScript(path -> {}, "verified");

    var libraries = provisioner.provision();

    assertThat(Files.readString(libraries.libreHardwareMonitor(), UTF_8)).isEqualTo("trusted");
    assertThat(Files.readString(libraries.cpuTemperatureScript(), UTF_8))
        .isEqualTo("trusted-script");
    Path directory = libraries.directory();
    libraries.close();
    assertThat(directory).doesNotExist();
  }

  private SecureNativeLibraryProvisioner provisionerWithLibraryAndScript(
      SecureNativeLibraryProvisioner.AclPolicy acl,
      String nonce) {
    return new SecureNativeLibraryProvisioner(
        tempDir,
        List.of(
            new SecureNativeLibraryProvisioner.ResourceSpec(
                "LibreHardwareMonitorLib.dll", hash("trusted"),
                () -> new ByteArrayInputStream("trusted".getBytes(UTF_8))),
            new SecureNativeLibraryProvisioner.ResourceSpec(
                "cpu-temperature.ps1", hash("trusted-script"),
                () -> new ByteArrayInputStream("trusted-script".getBytes(UTF_8)))),
        acl,
        () -> nonce);
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void productionAclRejectsBaseOwnedByInteractiveAttacker() throws Exception {
    Path attackerOwned = Files.createDirectory(tempDir.resolve("attacker-owned"));
    var interactiveUser = Files.getOwner(attackerOwned);
    assumeFalse(SecureNativeLibraryProvisioner.WindowsAclPolicy.trustedPrincipalNames().stream()
        .anyMatch(name -> name.equalsIgnoreCase(interactiveUser.getName())),
        "Test requires an untrusted interactive owner");

    assertThatThrownBy(() -> new SecureNativeLibraryProvisioner(
        attackerOwned).provision())
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("owner");
  }

  @Test
  void productionAclNeverGrantsAnArbitraryCurrentOwner() {
    assertThat(SecureNativeLibraryProvisioner.WindowsAclPolicy.trustedPrincipalNames())
        .containsExactlyInAnyOrder("NT AUTHORITY\\SYSTEM", "BUILTIN\\Administrators")
        .noneMatch(name -> name.contains(System.getProperty("user.name")));
  }

  @Test
  void configuredBaseRejectsSymbolicLinksOrReparsePoints() throws Exception {
    Path target = Files.createDirectory(tempDir.resolve("target"));
    Path linked = tempDir.resolve("linked");
    try {
      Files.createSymbolicLink(linked, target);
    } catch (UnsupportedOperationException | IOException failure) {
      org.junit.jupiter.api.Assumptions.abort("Symbolic links unavailable: " + failure.getMessage());
    }
    var provisioner = new SecureNativeLibraryProvisioner(
        linked, List.of(), path -> {}, () -> "linked");

    assertThatThrownBy(provisioner::provision)
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("link");
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
