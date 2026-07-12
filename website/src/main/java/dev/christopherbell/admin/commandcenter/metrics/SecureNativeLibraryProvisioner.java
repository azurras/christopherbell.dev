package dev.christopherbell.admin.commandcenter.metrics;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Extracts checksum-pinned native libraries only into an ACL-restricted fresh directory. */
final class SecureNativeLibraryProvisioner {
  static final String VERSION = "1.0.6";
  private final Path baseDirectory;
  private final List<ResourceSpec> resources;
  private final AclPolicy aclPolicy;
  private final Supplier<String> nonceSupplier;

  SecureNativeLibraryProvisioner(Path baseDirectory) {
    this(baseDirectory, List.of(
        resource("HidSharp.dll", "8c58e5fba22acc751032dfe97ce633e4f8a4c96089749bf316d55283b36649c2"),
        resource("LibreHardwareMonitorLib.dll", "a0f2728f1734c236a9d02d9e25a88bc4f8cb7bd1faff1770726beb7af06bf8dc")),
        new WindowsAclPolicy(), () -> UUID.randomUUID().toString());
  }

  SecureNativeLibraryProvisioner(
      Path baseDirectory,
      List<ResourceSpec> resources,
      AclPolicy aclPolicy,
      Supplier<String> nonceSupplier) {
    this.baseDirectory = baseDirectory.toAbsolutePath().normalize();
    this.resources = List.copyOf(resources);
    this.aclPolicy = aclPolicy;
    this.nonceSupplier = nonceSupplier;
  }

  NativeLibraries provision() {
    String nonce = nonceSupplier.get();
    if (nonce == null || !nonce.matches("[A-Za-z0-9-]{1,64}")) {
      throw new SecurityException("Invalid native library extraction nonce.");
    }
    Path versionDirectory = baseDirectory.resolve("jlibre-" + VERSION + "-" + nonce);
    boolean created = false;
    try {
      createDirectoriesWithoutLinks(baseDirectory);
      verifyNotLinkOrReparsePoint(baseDirectory);
      aclPolicy.hardenAndVerify(baseDirectory);
      Files.createDirectory(versionDirectory);
      created = true;
      verifyNotLinkOrReparsePoint(versionDirectory);
      aclPolicy.hardenAndVerify(versionDirectory);
      Path libre = null;
      for (var resource : resources) {
        Path target = versionDirectory.resolve(resource.fileName()).normalize();
        if (!target.getParent().equals(versionDirectory)) {
          throw new SecurityException("Invalid bundled native library name.");
        }
        try (InputStream input = resource.input().get()) {
          if (input == null) {
            throw new SecurityException("Bundled native library is missing.");
          }
          Files.copy(input, target);
        }
        verifyNotLinkOrReparsePoint(target);
        if (!resource.expectedSha256().equalsIgnoreCase(sha256(target))) {
          throw new SecurityException("Bundled native library checksum mismatch.");
        }
        aclPolicy.hardenAndVerify(target);
        if (!resource.expectedSha256().equalsIgnoreCase(sha256(target))) {
          throw new SecurityException("Native library changed after ACL hardening.");
        }
        if (resource.fileName().equals("LibreHardwareMonitorLib.dll")) {
          libre = target;
        }
      }
      if (libre == null) {
        throw new SecurityException("LibreHardwareMonitor library is missing.");
      }
      return NativeLibraries.owned(versionDirectory, libre);
    } catch (IOException | RuntimeException failure) {
      if (created) deleteTree(versionDirectory);
      if (failure instanceof SecurityException securityException) {
        throw securityException;
      }
      throw new SecurityException("Secure native library provisioning failed.", failure);
    }
  }

  private static void createDirectoriesWithoutLinks(Path directory) throws IOException {
    Path current = directory.getRoot();
    for (Path segment : directory) {
      current = current == null ? segment : current.resolve(segment);
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        verifyNotLinkOrReparsePoint(current);
      } else {
        Files.createDirectory(current);
        verifyNotLinkOrReparsePoint(current);
      }
    }
  }

  private static void verifyNotLinkOrReparsePoint(Path path) throws IOException {
    var attributes = Files.readAttributes(
        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (attributes.isSymbolicLink() || attributes.isOther()) {
      throw new SecurityException("Native library path contains a link or reparse point.");
    }
  }

  private static ResourceSpec resource(String fileName, String hash) {
    return new ResourceSpec(fileName, hash,
        () -> SecureNativeLibraryProvisioner.class.getResourceAsStream("/lib/" + fileName));
  }

  private static String sha256(Path path) throws IOException {
    try (InputStream input = Files.newInputStream(path)) {
      var digest = MessageDigest.getInstance("SHA-256");
      input.transferTo(new java.io.OutputStream() {
        @Override public void write(int value) { digest.update((byte) value); }
        @Override public void write(byte[] bytes, int offset, int length) {
          digest.update(bytes, offset, length);
        }
      });
      return HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static void deleteTree(Path directory) {
    if (directory == null || !Files.exists(directory)) return;
    try (var paths = Files.walk(directory)) {
      paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
      });
    } catch (IOException ignored) { }
  }

  record ResourceSpec(String fileName, String expectedSha256, Supplier<InputStream> input) {}

  @FunctionalInterface
  interface AclPolicy {
    void hardenAndVerify(Path path) throws IOException;
  }

  static final class NativeLibraries implements AutoCloseable {
    private final Path directory;
    private final Path libreHardwareMonitor;
    private final boolean owned;

    NativeLibraries(Path directory, Path libreHardwareMonitor) {
      this(directory, libreHardwareMonitor, false);
    }

    private NativeLibraries(Path directory, Path libreHardwareMonitor, boolean owned) {
      this.directory = directory;
      this.libreHardwareMonitor = libreHardwareMonitor;
      this.owned = owned;
    }

    static NativeLibraries owned(Path directory, Path libre) {
      return new NativeLibraries(directory, libre, true);
    }

    Path directory() { return directory; }
    Path libreHardwareMonitor() { return libreHardwareMonitor; }
    @Override public void close() { if (owned) deleteTree(directory); }
  }

  static final class WindowsAclPolicy implements AclPolicy {
    private static final Set<String> TRUSTED_PRINCIPAL_NAMES = Set.of(
        "NT AUTHORITY\\SYSTEM", "BUILTIN\\Administrators");
    private static final Set<AclEntryPermission> DANGEROUS_PERMISSIONS = EnumSet.of(
        AclEntryPermission.WRITE_DATA,
        AclEntryPermission.APPEND_DATA,
        AclEntryPermission.WRITE_NAMED_ATTRS,
        AclEntryPermission.WRITE_ATTRIBUTES,
        AclEntryPermission.DELETE,
        AclEntryPermission.DELETE_CHILD,
        AclEntryPermission.WRITE_ACL,
        AclEntryPermission.WRITE_OWNER);

    static Set<String> trustedPrincipalNames() {
      return TRUSTED_PRINCIPAL_NAMES;
    }

    @Override
    public void hardenAndVerify(Path path) throws IOException {
      var view = Files.getFileAttributeView(path, AclFileAttributeView.class);
      var ownerView = Files.getFileAttributeView(path, FileOwnerAttributeView.class);
      if (view == null || ownerView == null) {
        throw new SecurityException("Windows ACL support is required.");
      }
      var lookup = path.getFileSystem().getUserPrincipalLookupService();
      var system = lookupPrincipal(lookup, "NT AUTHORITY\\SYSTEM", "SYSTEM", false);
      var administrators = lookupPrincipal(
          lookup, "BUILTIN\\Administrators", "Administrators", true);
      var owner = ownerView.getOwner();
      if (!owner.equals(system) && !owner.equals(administrators)) {
        throw new SecurityException("Native library base owner is not trusted.");
      }
      ownerView.setOwner(system);
      var permissions = EnumSet.allOf(AclEntryPermission.class);
      var flags = Files.isDirectory(path)
          ? EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
          : EnumSet.noneOf(AclEntryFlag.class);
      var principals = List.of(system, administrators);
      var entries = new ArrayList<AclEntry>();
      for (var principal : principals.stream().distinct().toList()) {
        entries.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(principal)
            .setPermissions(permissions).setFlags(flags).build());
      }
      view.setAcl(entries);
      Set<String> allowed = principals.stream()
          .map(principal -> principal.getName().toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
      for (var entry : view.getAcl()) {
        if (entry.type() == AclEntryType.ALLOW
            && !allowed.contains(entry.principal().getName().toLowerCase(Locale.ROOT))
            && entry.permissions().stream().anyMatch(DANGEROUS_PERMISSIONS::contains)) {
          throw new SecurityException("Untrusted principal retains native library write access.");
        }
      }
      if (!ownerView.getOwner().equals(system)) {
        throw new SecurityException("Native library owner could not be restricted to SYSTEM.");
      }
    }

    private static java.nio.file.attribute.UserPrincipal lookupPrincipal(
        java.nio.file.attribute.UserPrincipalLookupService lookup,
        String qualified,
        String fallback,
        boolean group) throws IOException {
      try {
        return group ? lookup.lookupPrincipalByGroupName(qualified)
            : lookup.lookupPrincipalByName(qualified);
      } catch (java.nio.file.attribute.UserPrincipalNotFoundException failure) {
        return group ? lookup.lookupPrincipalByGroupName(fallback)
            : lookup.lookupPrincipalByName(fallback);
      }
    }
  }
}
