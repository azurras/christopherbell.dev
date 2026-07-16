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
  void usesPinnedLibreHardwareMonitorVersion() {
    assertThat(SecureNativeLibraryProvisioner.VERSION).isEqualTo("0.9.6");
  }

  @Test
  void publishesCompleteFreshResourcesBeforeRetiringStaleSiblings() throws Exception {
    Path stale = Files.createDirectories(
        tempDir.resolve("librehardwaremonitor-0.9.6-stale"));
    Files.writeString(stale.resolve("LibreHardwareMonitorLib.dll"), "malicious", UTF_8);
    Path fresh = tempDir.resolve("librehardwaremonitor-0.9.6-fresh");
    boolean[] finalVisibleDuringCopy = {false};
    boolean[] finalCompleteDuringCleanup = {false};
    boolean[] ownerMarkerVisibleDuringCleanup = {false};
    var provisioner = provisionerWithLibraryCompanionAndScript(path -> {
      if (path.equals(stale)) {
        finalCompleteDuringCleanup[0] =
            Files.exists(fresh.resolve("LibreHardwareMonitorLib.dll"))
                && Files.exists(fresh.resolve("cpu-temperature.ps1"));
        ownerMarkerVisibleDuringCleanup[0] =
            Files.exists(fresh.resolve("live-owner.pid"));
      }
    }, "fresh", () -> finalVisibleDuringCopy[0] |= Files.exists(fresh));

    var libraries = provisioner.provision();

    assertThat(finalVisibleDuringCopy[0]).isFalse();
    assertThat(finalCompleteDuringCleanup[0]).isTrue();
    assertThat(ownerMarkerVisibleDuringCleanup[0]).isFalse();
    assertThat(stale).doesNotExist();
    assertThat(libraries.directory()).isEqualTo(fresh);
    assertThat(Files.readString(libraries.libreHardwareMonitor(), UTF_8))
        .isEqualTo("trusted");
    String marker = Files.readString(fresh.resolve("live-owner.pid"), UTF_8);
    assertThat(marker).contains("pid=" + ProcessHandle.current().pid());
    assertThat(marker).contains("startedAtEpochMillis=");
    libraries.close();
  }

  @Test
  void holdsExclusiveLeaseForTheOwnedResourceLifetime() throws Exception {
    var first = provisionerWithLibraryCompanionAndScript(path -> {}, "first");
    var second = provisionerWithLibraryCompanionAndScript(path -> {}, "second");
    var firstLibraries = first.provision();

    assertThatThrownBy(second::provision)
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("already in use");

    firstLibraries.close();
    var secondLibraries = second.provision();
    secondLibraries.close();
  }

  @Test
  void reportsStrictRollbackFailureWithoutPublishingAnOwnerMarker() throws Exception {
    Path outside = Files.writeString(tempDir.resolve("rollback-outside.txt"), "outside", UTF_8);
    Path stale = Files.createDirectories(
        tempDir.resolve("librehardwaremonitor-0.9.6-rollback-stale"));
    try {
      Files.createSymbolicLink(stale.resolve("linked.txt"), outside);
    } catch (UnsupportedOperationException | IOException failure) {
      org.junit.jupiter.api.Assumptions.abort(
          "Symbolic links unavailable: " + failure.getMessage());
    }
    Path fresh = tempDir.resolve("librehardwaremonitor-0.9.6-rollback-fresh");
    int[] freshAclVisits = {0};
    var provisioner = provisionerWithLibraryCompanionAndScript(path -> {
      if (path.equals(fresh) && ++freshAclVisits[0] > 1) {
        throw new IOException("rollback blocked");
      }
    }, "rollback-fresh");

    assertThatThrownBy(provisioner::provision)
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("rollback failed");
    assertThat(fresh.resolve("live-owner.pid")).doesNotExist();
    assertThat(outside).exists();
  }

  @Test
  void checksumMismatchFailsClosedAndRemovesCreatedVersionDirectory() {
    var provisioner = provisioner("tampered", hash("trusted"), path -> {}, "mismatch");

    assertThatThrownBy(provisioner::provision).isInstanceOf(SecurityException.class);
    assertThat(tempDir.resolve("librehardwaremonitor-0.9.6-mismatch")).doesNotExist();
  }

  @Test
  void aclHardeningFailureFailsClosedBeforeAnyLibraryIsWritten() {
    var provisioner = provisioner("trusted", hash("trusted"), path -> {
      throw new SecurityException("ACL rejected");
    }, "acl-failure");

    assertThatThrownBy(provisioner::provision).isInstanceOf(SecurityException.class);
    assertThat(tempDir.resolve(
        "librehardwaremonitor-0.9.6-acl-failure/LibreHardwareMonitorLib.dll"))
        .doesNotExist();
  }

  @Test
  void verifiedExtractionReturnsPrimaryLibraryCompanionAndScriptThenCleansUp() throws Exception {
    var provisioner = provisionerWithLibraryCompanionAndScript(path -> {}, "verified");

    var libraries = provisioner.provision();

    assertThat(Files.readString(libraries.libreHardwareMonitor(), UTF_8)).isEqualTo("trusted");
    assertThat(Files.readString(libraries.directory().resolve("HidSharp.dll"), UTF_8))
        .isEqualTo("trusted-hid");
    assertThat(Files.readString(libraries.cpuTemperatureScript(), UTF_8))
        .isEqualTo("trusted-script");
    Path directory = libraries.directory();
    libraries.close();
    assertThat(directory).doesNotExist();
  }

  private SecureNativeLibraryProvisioner provisionerWithLibraryCompanionAndScript(
      SecureNativeLibraryProvisioner.AclPolicy acl,
      String nonce) {
    return provisionerWithLibraryCompanionAndScript(acl, nonce, () -> {});
  }

  private SecureNativeLibraryProvisioner provisionerWithLibraryCompanionAndScript(
      SecureNativeLibraryProvisioner.AclPolicy acl,
      String nonce,
      Runnable beforeResourceCopy) {
    return new SecureNativeLibraryProvisioner(
        tempDir,
        List.of(
            new SecureNativeLibraryProvisioner.ResourceSpec(
                "LibreHardwareMonitorLib.dll", hash("trusted"),
                () -> {
                  beforeResourceCopy.run();
                  return new ByteArrayInputStream("trusted".getBytes(UTF_8));
                }),
            new SecureNativeLibraryProvisioner.ResourceSpec(
                "HidSharp.dll", hash("trusted-hid"),
                () -> {
                  beforeResourceCopy.run();
                  return new ByteArrayInputStream("trusted-hid".getBytes(UTF_8));
                }),
            new SecureNativeLibraryProvisioner.ResourceSpec(
                "cpu-temperature.ps1", hash("trusted-script"),
                () -> {
                  beforeResourceCopy.run();
                  return new ByteArrayInputStream("trusted-script".getBytes(UTF_8));
                })),
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

  @Test
  void staleCleanupRefusesSymbolicLinksWithoutDeletingTheirTarget() throws Exception {
    Path outside = Files.writeString(tempDir.resolve("outside.txt"), "outside", UTF_8);
    Path stale = Files.createDirectories(
        tempDir.resolve("librehardwaremonitor-0.9.6-stale-link"));
    try {
      Files.createSymbolicLink(stale.resolve("linked.txt"), outside);
    } catch (UnsupportedOperationException | IOException failure) {
      org.junit.jupiter.api.Assumptions.abort(
          "Symbolic links unavailable: " + failure.getMessage());
    }
    var provisioner = provisionerWithLibraryCompanionAndScript(
        path -> {}, "fresh-link");

    assertThatThrownBy(provisioner::provision)
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("link");
    assertThat(outside).exists();
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
