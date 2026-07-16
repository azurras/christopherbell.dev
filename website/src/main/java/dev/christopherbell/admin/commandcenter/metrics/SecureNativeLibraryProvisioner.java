package dev.christopherbell.admin.commandcenter.metrics;

import com.sun.jna.platform.win32.AccCtrl;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinNT;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
  static final String VERSION = "0.9.6";
  private static final String DIRECTORY_PREFIX = "librehardwaremonitor-" + VERSION + "-";
  private static final String STAGING_PREFIX = ".librehardwaremonitor-" + VERSION + "-";
  private static final String STAGING_SUFFIX = "-staging";
  private static final String LEASE_FILE = "librehardwaremonitor.lock";
  private static final String VALID_DIRECTORY_NAME =
      java.util.regex.Pattern.quote(DIRECTORY_PREFIX) + "[A-Za-z0-9-]{1,64}";
  private static final String VALID_STAGING_NAME =
      java.util.regex.Pattern.quote(STAGING_PREFIX)
          + "[A-Za-z0-9-]{1,64}"
          + java.util.regex.Pattern.quote(STAGING_SUFFIX);
  private final Path baseDirectory;
  private final List<ResourceSpec> resources;
  private final AclPolicy aclPolicy;
  private final Supplier<String> nonceSupplier;

  SecureNativeLibraryProvisioner(Path baseDirectory) {
    this(baseDirectory, List.of(
        resource("LibreHardwareMonitorLib.dll", "6ebc194316536ba61af5be24508ad9fcbb2ecc685e716c12e787c79530f66bf0"),
        resource("HidSharp.dll", "d86690efde30ea9179f669320f39148853793b743a98b531afeaf30598e22f54"),
        resource("BlackSharp.Core.dll", "cafb93afcc8d8a367e21f619673d05c06887d8964867fed1371f02ded1cd3e23"),
        resource("DiskInfoToolkit.dll", "1acbf51b3c10c51c986cf43021680d34a2e38d9a5ba652bcfa9a1b5f7fc09800"),
        resource("RAMSPDToolkit-NDD.dll", "b6882354c7c8ec186617e421507743dbfae09c5c1fc24cef76a1d0c0c26651de"),
        resource("System.Memory.dll", "d5e8e4866f9cfa66f7765660f84b210198893e55335487afe5ebda342c0e913d"),
        resource("System.Runtime.CompilerServices.Unsafe.dll", "08cbd7278b66f1e68425a82d4b97181a4130d93e3dd91831407aba7212ccdacf"),
        resource("cpu-temperature.ps1", "f90a50a607b3c714512a4cf9070339cb8e03ac2759e649be68f907bb75aee30b")),
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
    Path versionDirectory = baseDirectory.resolve(DIRECTORY_PREFIX + nonce);
    Path stagingDirectory =
        baseDirectory.resolve(STAGING_PREFIX + nonce + STAGING_SUFFIX);
    Path ownedDirectory = null;
    SensorLease lease = null;
    try {
      createTrustedBaseDirectory();
      verifyNotLinkOrReparsePoint(baseDirectory);
      aclPolicy.hardenAndVerify(baseDirectory);
      lease = acquireSensorLease();
      removeOwnedDirectories(STAGING_PREFIX + "*", VALID_STAGING_NAME, null);
      Files.createDirectory(stagingDirectory);
      ownedDirectory = stagingDirectory;
      verifyNotLinkOrReparsePoint(stagingDirectory);
      aclPolicy.hardenAndVerify(stagingDirectory);
      Path libre = null;
      Path cpuTemperatureScript = null;
      for (var resource : resources) {
        Path target = stagingDirectory.resolve(resource.fileName()).normalize();
        if (!target.getParent().equals(stagingDirectory)) {
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
        } else if (resource.fileName().equals("cpu-temperature.ps1")) {
          cpuTemperatureScript = target;
        }
      }
      if (libre == null || cpuTemperatureScript == null) {
        throw new SecurityException("Required CPU sensor resources are missing.");
      }
      Files.move(stagingDirectory, versionDirectory, StandardCopyOption.ATOMIC_MOVE);
      ownedDirectory = versionDirectory;
      verifyNotLinkOrReparsePoint(versionDirectory);
      aclPolicy.hardenAndVerify(versionDirectory);
      Path finalLibre = versionDirectory.resolve(libre.getFileName());
      Path finalScript = versionDirectory.resolve(cpuTemperatureScript.getFileName());
      removeOwnedDirectories(DIRECTORY_PREFIX + "*", VALID_DIRECTORY_NAME, versionDirectory);
      publishOwnerMarker(versionDirectory);
      return NativeLibraries.owned(
          versionDirectory, finalLibre, finalScript, lease);
    } catch (IOException | RuntimeException failure) {
      try {
        if (ownedDirectory != null
            && Files.exists(ownedDirectory, LinkOption.NOFOLLOW_LINKS)) {
          deleteOwnedTreeStrict(ownedDirectory);
        }
      } catch (IOException | RuntimeException rollbackFailure) {
        var combined = new SecurityException(
            "Secure native library provisioning rollback failed.",
            rollbackFailure);
        combined.addSuppressed(failure);
        throw combined;
      } finally {
        if (lease != null) lease.close();
      }
      if (failure instanceof SecurityException securityException) {
        throw securityException;
      }
      throw new SecurityException("Secure native library provisioning failed.", failure);
    }
  }

  private void createTrustedBaseDirectory() throws IOException {
    if (aclPolicy instanceof WindowsAclPolicy) {
      createDirectoriesWithoutLinks(baseDirectory);
      return;
    }
    if (Files.exists(baseDirectory, LinkOption.NOFOLLOW_LINKS)) {
      verifyNotLinkOrReparsePoint(baseDirectory);
      return;
    }
    Files.createDirectories(baseDirectory);
    verifyNotLinkOrReparsePoint(baseDirectory);
  }

  private void removeOwnedDirectories(
      String glob,
      String validNamePattern,
      Path excludedDirectory) throws IOException {
    var trees = new ArrayList<List<Path>>();
    try (var candidates = Files.newDirectoryStream(baseDirectory, glob)) {
      for (Path candidate : candidates) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!baseDirectory.equals(normalized.getParent())
            || !normalized.getFileName().toString().matches(validNamePattern)) {
          throw new SecurityException("Invalid native library resource directory.");
        }
        verifyNotLinkOrReparsePoint(normalized);
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
          throw new SecurityException("Native library resource is not a directory.");
        }
        if (excludedDirectory != null && excludedDirectory.equals(normalized)) {
          continue;
        }
        var tree = new ArrayList<Path>();
        collectTrustedTree(normalized, tree);
        trees.add(tree);
      }
    }
    for (List<Path> tree : trees) {
      tree.sort(java.util.Comparator.reverseOrder());
      for (Path path : tree) {
        Files.delete(path);
      }
    }
  }

  private void deleteOwnedTreeStrict(Path directory) throws IOException {
    var tree = new ArrayList<Path>();
    collectTrustedTree(directory, tree);
    tree.sort(java.util.Comparator.reverseOrder());
    for (Path path : tree) {
      Files.delete(path);
    }
  }

  private void collectTrustedTree(Path path, List<Path> paths) throws IOException {
    verifyNotLinkOrReparsePoint(path);
    aclPolicy.hardenAndVerify(path);
    paths.add(path);
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return;
    try (var children = Files.newDirectoryStream(path)) {
      for (Path child : children) {
        Path normalized = child.toAbsolutePath().normalize();
        if (!path.equals(normalized.getParent())) {
          throw new SecurityException("Stale native library child escaped its directory.");
        }
        collectTrustedTree(normalized, paths);
      }
    }
  }

  private SensorLease acquireSensorLease() throws IOException {
    Path leasePath = baseDirectory.resolve(LEASE_FILE);
    if (Files.exists(leasePath, LinkOption.NOFOLLOW_LINKS)) {
      verifyNotLinkOrReparsePoint(leasePath);
    } else {
      try {
        Files.createFile(leasePath);
      } catch (java.nio.file.FileAlreadyExistsException ignored) {
        verifyNotLinkOrReparsePoint(leasePath);
      }
    }
    verifyNotLinkOrReparsePoint(leasePath);
    aclPolicy.hardenAndVerify(leasePath);
    FileChannel channel = FileChannel.open(leasePath, StandardOpenOption.WRITE);
    try {
      FileLock lock = channel.tryLock();
      if (lock == null) {
        throw new SecurityException("CPU sensor resources are already in use.");
      }
      return new SensorLease(channel, lock);
    } catch (OverlappingFileLockException failure) {
      var wrapped = new SecurityException(
          "CPU sensor resources are already in use.", failure);
      closeFailedLeaseChannel(channel, wrapped);
      throw wrapped;
    } catch (IOException | RuntimeException failure) {
      closeFailedLeaseChannel(channel, failure);
      throw failure;
    }
  }

  private static void closeFailedLeaseChannel(FileChannel channel, Throwable failure) {
    try {
      channel.close();
    } catch (IOException closeFailure) {
      failure.addSuppressed(closeFailure);
    }
  }

  private void publishOwnerMarker(Path directory) throws IOException {
    long ownerPid = ProcessHandle.current().pid();
    long startedAtEpochMillis = ProcessHandle.current().info().startInstant()
        .orElseThrow(() -> new SecurityException(
            "CPU sensor owner process start time is unavailable."))
        .toEpochMilli();
    String owner = "pid=" + ownerPid + System.lineSeparator()
        + "startedAtEpochMillis=" + startedAtEpochMillis + System.lineSeparator();
    Path stagingMarker = directory.resolve(".live-owner.pid-staging");
    Path ownerMarker = directory.resolve("live-owner.pid");
    Files.writeString(
        stagingMarker,
        owner,
        java.nio.charset.StandardCharsets.US_ASCII,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE);
    verifyNotLinkOrReparsePoint(stagingMarker);
    aclPolicy.hardenAndVerify(stagingMarker);
    if (!owner.equals(Files.readString(
        stagingMarker, java.nio.charset.StandardCharsets.US_ASCII))) {
      throw new SecurityException("CPU sensor owner marker changed before publication.");
    }
    Files.move(stagingMarker, ownerMarker, StandardCopyOption.ATOMIC_MOVE);
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
    private final Path cpuTemperatureScript;
    private final boolean owned;
    private final SensorLease lease;

    NativeLibraries(Path directory, Path libreHardwareMonitor) {
      this(directory, libreHardwareMonitor, directory.resolve("cpu-temperature.ps1"), false, null);
    }

    NativeLibraries(Path directory, Path libreHardwareMonitor, Path cpuTemperatureScript) {
      this(directory, libreHardwareMonitor, cpuTemperatureScript, false, null);
    }

    private NativeLibraries(
        Path directory,
        Path libreHardwareMonitor,
        Path cpuTemperatureScript,
        boolean owned,
        SensorLease lease) {
      this.directory = directory;
      this.libreHardwareMonitor = libreHardwareMonitor;
      this.cpuTemperatureScript = cpuTemperatureScript;
      this.owned = owned;
      this.lease = lease;
    }

    static NativeLibraries owned(Path directory, Path libre) {
      return new NativeLibraries(
          directory, libre, directory.resolve("cpu-temperature.ps1"), true, null);
    }

    static NativeLibraries owned(Path directory, Path libre, Path script) {
      return new NativeLibraries(directory, libre, script, true, null);
    }

    static NativeLibraries owned(
        Path directory, Path libre, Path script, SensorLease lease) {
      return new NativeLibraries(directory, libre, script, true, lease);
    }

    Path directory() { return directory; }
    Path libreHardwareMonitor() { return libreHardwareMonitor; }
    Path cpuTemperatureScript() { return cpuTemperatureScript; }
    @Override
    public void close() {
      if (owned) deleteTree(directory);
      if (lease != null) lease.close();
    }
  }

  private static final class SensorLease implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private SensorLease(FileChannel channel, FileLock lock) {
      this.channel = channel;
      this.lock = lock;
    }

    @Override
    public void close() {
      try {
        if (lock.isValid()) lock.release();
      } catch (IOException ignored) {
      } finally {
        try {
          channel.close();
        } catch (IOException ignored) {
        }
      }
    }
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
      protectDacl(path);
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

    /** Reapplies the current DACL while disabling inheritance, which Java NIO cannot express. */
    static void protectDacl(Path path) {
      var descriptor = Advapi32Util.getFileSecurityDescriptor(path.toFile(), false);
      var dacl = descriptor.getDiscretionaryACL();
      if (dacl == null) {
        throw new SecurityException("Windows ACL has no discretionary access list.");
      }
      int result = Advapi32.INSTANCE.SetNamedSecurityInfo(
          path.toAbsolutePath().toString(),
          AccCtrl.SE_OBJECT_TYPE.SE_FILE_OBJECT,
          WinNT.DACL_SECURITY_INFORMATION | WinNT.PROTECTED_DACL_SECURITY_INFORMATION,
          null,
          null,
          dacl.getPointer(),
          null);
      if (result != 0) {
        throw new SecurityException(
            "Windows ACL inheritance could not be disabled.",
            new Win32Exception(result));
      }
      descriptor = Advapi32Util.getFileSecurityDescriptor(path.toFile(), false);
      if ((descriptor.Control & WinNT.SE_DACL_PROTECTED) == 0) {
        throw new SecurityException("Windows ACL inheritance remains enabled.");
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
