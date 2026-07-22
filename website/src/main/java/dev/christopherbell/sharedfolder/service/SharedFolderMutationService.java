package dev.christopherbell.sharedfolder.service;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.sharedfolder.fs.SharedFolderPathResolver;
import dev.christopherbell.sharedfolder.fs.PortableSharedFolderPrivateBoundary;
import dev.christopherbell.sharedfolder.fs.PortableSharedFolderPrivateBoundary.BoundaryUnavailableException;
import dev.christopherbell.sharedfolder.fs.PortableSharedFolderPrivateBoundary.FileAccess;
import dev.christopherbell.sharedfolder.fs.UnsafeSharedPathException;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderMutationBoundary;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeBoundaryException;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeFileMetadata;
import dev.christopherbell.sharedfolder.model.SharedDirectoryEntry;
import dev.christopherbell.sharedfolder.model.SharedDirectoryEntryType;
import dev.christopherbell.sharedfolder.model.SharedFolderCreateFolderRequest;
import dev.christopherbell.sharedfolder.model.SharedFolderDeleteRequest;
import dev.christopherbell.sharedfolder.model.SharedFolderMoveRequest;
import dev.christopherbell.sharedfolder.model.SharedFolderPreviewKind;
import dev.christopherbell.sharedfolder.model.SharedFolderRenameRequest;
import dev.christopherbell.sharedfolder.security.SharedFolderAccessService;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.channels.Channels;
import java.security.MessageDigest;
import java.util.UUID;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Performs conflict-safe shared-folder mutations after a fresh write authorization decision. */
@Service
public class SharedFolderMutationService {
  private static final java.time.Duration OPERATION_LEASE_TTL = java.time.Duration.ofMinutes(2);
  private static final long LEASE_RENEWAL_BYTES = 1024L * 1024L;
  private final SharedFolderAccessService access;
  private final SharedFolderProperties properties;
  private final WindowsSharedFolderMutationBoundary nativeBoundary;
  private final SharedFolderMutationRecoveryRepository recoveries;
  private final SharedFolderAccountMutationLimiter mutationLimiter;
  private final PortableSharedFolderPrivateBoundary privateBoundary;
  private final String serviceInstanceId = UUID.randomUUID().toString();

  /** Creates the portable mutation service used by non-Windows test and local providers. */
  public SharedFolderMutationService(
      SharedFolderAccessService access, SharedFolderProperties properties) {
    this(access, properties, WindowsSharedFolderMutationBoundary.inactive(), null, null);
  }

  /** Creates the production service; Windows held-root mode must never fall back to NIO writes. */
  public SharedFolderMutationService(
      SharedFolderAccessService access,
      SharedFolderProperties properties,
      WindowsSharedFolderMutationBoundary nativeBoundary) {
    this(access, properties, nativeBoundary, null, null);
  }

  /** Creates the production service with a durable conditional-replacement recovery journal. */
  public SharedFolderMutationService(
      SharedFolderAccessService access,
      SharedFolderProperties properties,
      WindowsSharedFolderMutationBoundary nativeBoundary,
      SharedFolderMutationRecoveryRepository recoveries) {
    this(access, properties, nativeBoundary, recoveries, null);
  }

  /** Creates the production service with durable recovery and account mutation admission. */
  @Autowired
  public SharedFolderMutationService(
      SharedFolderAccessService access,
      SharedFolderProperties properties,
      WindowsSharedFolderMutationBoundary nativeBoundary,
      SharedFolderMutationRecoveryRepository recoveries,
      SharedFolderAccountMutationLimiter mutationLimiter) {
    this.access = access;
    this.properties = properties;
    this.nativeBoundary = nativeBoundary;
    this.recoveries = recoveries;
    this.mutationLimiter = mutationLimiter;
    this.privateBoundary = nativeBoundary.testOnlyPortableMode()
        ? PortableSharedFolderPrivateBoundary.testOnlyWithPathMoves(properties.systemRoot())
        : new PortableSharedFolderPrivateBoundary(properties.systemRoot());
  }

  /** Creates one new directory under a decoded, existing relative parent path. */
  public SharedDirectoryEntry createFolder(SharedFolderCreateFolderRequest request) {
    Account account = access.requireWrite();
    requireMutationCapacity(account);
    validateCreateFolder(request);
    if (!properties.enabled()) {
      throw unavailable();
    }
    requireRetainedVisibleMutationBoundary();
    reconcileOwner(account);
    if (nativeBoundary.nativeMode()) {
      try {
        String relative = relativePath(request.parentPath(), request.name());
        return describeNative(relative,
            nativeBoundary.createDirectory(request.parentPath(), request.name()));
      } catch (NativeBoundaryException exception) {
        throw nativeFailure(exception);
      } catch (SecurityException exception) {
        throw unavailable();
      }
    }
    requirePortableBoundary();
    try {
      SharedFolderPathResolver resolver = portableResolver();
      Path target = resolver.newChild(request.parentPath(), request.name());
      Path parent = target.getParent();
      resolver.recheckForMutation(parent);
      Files.createDirectory(target);
      BasicFileAttributes attributes = Files.readAttributes(target, BasicFileAttributes.class);
      return new SharedDirectoryEntry(
          request.name(), relativePath(request.parentPath(), request.name()),
          SharedDirectoryEntryType.DIRECTORY, 0, attributes.lastModifiedTime().toInstant(),
          SharedFolderPreviewKind.NONE,
          SharedFolderObservedItemTokens.token(relativePath(request.parentPath(), request.name()), attributes));
    } catch (FileAlreadyExistsException exception) {
      throw conflict();
    } catch (IOException | SecurityException exception) {
      throw notFound();
    }
  }

  /** Returns an opaque observation for a currently existing relative item. */
  public String observedToken(String path) {
    access.requireRead();
    validateRelativePath(path, true);
    if (!properties.enabled()) {
      throw unavailable();
    }
    try {
      if (nativeBoundary.nativeMode()) {
        return nativeToken(path, nativeBoundary.metadata(path));
      }
      requirePortableBoundary();
      SharedFolderPathResolver resolver = portableResolver();
      return currentToken(resolver, path, resolver.existing(path));
    } catch (NativeBoundaryException exception) {
      throw nativeFailure(exception);
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (SecurityException exception) {
      throw notFound();
    }
  }

  /** Renames one observed item without implicitly replacing an existing target. */
  public SharedDirectoryEntry rename(SharedFolderRenameRequest request) {
    Account account = access.requireWrite();
    requireMutationCapacity(account);
    validateRename(request);
    if (!properties.enabled()) {
      throw unavailable();
    }
    requireRetainedVisibleMutationBoundary();
    reconcileOwner(account);
    String parentPath = parentPath(request.path());
    String targetRelative = relativePath(parentPath, request.name());
    if (nativeBoundary.nativeMode()) {
      try {
        NativeFileMetadata observed = nativeBoundary.metadata(request.path());
        requireNativeToken(request.path(), observed, request.observedToken());
        return describeNative(targetRelative, nativeBoundary.rename(
            request.path(), parentPath, request.name(), false, observed));
      } catch (ResponseStatusException exception) {
        throw exception;
      } catch (NativeBoundaryException exception) {
        throw nativeFailure(exception);
      } catch (SecurityException exception) {
        throw unavailable();
      }
    }
    requirePortableBoundary();
    try {
      SharedFolderPathResolver resolver = portableResolver();
      Path source = resolver.existing(request.path());
      Path target = resolver.newChild(parentPath, request.name());
      boolean caseOnlyRequest = !targetRelative.equals(request.path())
          && targetRelative.equalsIgnoreCase(request.path());
      boolean sameObjectCaseOnly = false;
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        if (!caseOnlyRequest || !Files.isSameFile(source, target)) {
          throw conflict();
        }
        sameObjectCaseOnly = true;
      }
      requireCurrentToken(resolver, request.path(), source, request.observedToken());
      resolver.recheckForMutation(source);
      resolver.recheckForMutation(source.getParent());
      requireCurrentToken(resolver, request.path(), source, request.observedToken());
      if (sameObjectCaseOnly) {
        moveCaseOnly(source, target, resolver);
      } else {
        moveAtomically(source, target, false);
      }
      return describe(targetRelative, target);
    } catch (FileAlreadyExistsException exception) {
      throw conflict();
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (IOException | SecurityException exception) {
      throw notFound();
    }
  }

  /** Moves one observed item and permits replacement only with an explicit target observation. */
  public SharedDirectoryEntry move(SharedFolderMoveRequest request) {
    Account account = access.requireWrite();
    requireMutationCapacity(account);
    validateMove(request);
    if (!properties.enabled()) {
      throw unavailable();
    }
    requireRetainedVisibleMutationBoundary();
    reconcileOwner(account);
    String targetName = request.name();
    String targetRelative = relativePath(request.destinationPath(), targetName);
    if (nativeBoundary.nativeMode()) {
      try {
        if (targetRelative.equals(request.path())) {
          throw conflict();
        }
        NativeFileMetadata observed = nativeBoundary.metadata(request.path());
        requireNativeToken(request.path(), observed, request.observedToken());
        NativeFileMetadata replaced = null;
        SharedFolderMoveRequest effectiveRequest = request;
        if (request.replace()) {
          if (request.replacedObservedToken() == null) {
            throw conflict();
          }
          try {
            targetName = nativeBoundary.canonicalChildName(
                request.destinationPath(), request.name());
            targetRelative = relativePath(request.destinationPath(), targetName);
            effectiveRequest = new SharedFolderMoveRequest(
                request.path(), request.destinationPath(), targetName, request.observedToken(),
                true, request.replacedObservedToken());
            replaced = nativeBoundary.metadata(targetRelative);
          } catch (NativeBoundaryException exception) {
            if (isNativeMissing(exception)) throw conflict();
            throw exception;
          }
          requireNativeToken(targetRelative, replaced, request.replacedObservedToken());
        }
        if (replaced != null && recoveries != null) {
          SharedFolderMutationRecovery recovery = prepareNativeRecovery(
              account, effectiveRequest, observed, replaced);
          afterPhysicalMutationTransition(SharedFolderMutationRecoveryState.PREPARED);
          return describeNative(targetRelative,
              replaceNativeThroughDurableQuarantine(recovery, observed, replaced));
        }
        return describeNative(targetRelative, nativeBoundary.rename(
            request.path(), request.destinationPath(), targetName, request.replace(), observed, replaced));
      } catch (ResponseStatusException exception) {
        throw exception;
      } catch (NativeBoundaryException exception) {
        throw nativeFailure(exception);
      } catch (SecurityException exception) {
        throw unavailable();
      }
    }
    requirePortableBoundary();
    try {
      SharedFolderPathResolver resolver = portableResolver();
      Path source = resolver.existing(request.path());
      Path target = resolver.newChild(request.destinationPath(), request.name());
      if (targetRelative.equals(request.path())) {
        throw conflict();
      }
      boolean targetExists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
      if (request.replace() && !targetExists) {
        throw conflict();
      }
      Path existingTarget = null;
      SharedFolderMoveRequest effectiveRequest = request;
      if (targetExists) {
        if (!request.replace() || request.replacedObservedToken() == null) {
          throw conflict();
        }
        existingTarget = resolver.existing(targetRelative);
        targetName = existingTarget.toRealPath(LinkOption.NOFOLLOW_LINKS)
            .getFileName().toString();
        targetRelative = relativePath(request.destinationPath(), targetName);
        target = resolver.newChild(request.destinationPath(), targetName);
        existingTarget = resolver.existing(targetRelative);
        effectiveRequest = new SharedFolderMoveRequest(
            request.path(), request.destinationPath(), targetName, request.observedToken(),
            true, request.replacedObservedToken());
        requireCurrentToken(resolver, targetRelative, existingTarget, request.replacedObservedToken());
      }
      requireCurrentToken(resolver, request.path(), source, request.observedToken());
      resolver.recheckForMutation(source);
      resolver.recheckForMutation(target.getParent());
      requireSameFileStore(source, target.getParent());
      requireCurrentToken(resolver, request.path(), source, request.observedToken());
      if (existingTarget != null) {
        requireCurrentToken(resolver, targetRelative, existingTarget, request.replacedObservedToken());
        requirePortableReplacementTarget(existingTarget);
        if (recoveries == null) {
          replaceThroughQuarantine(source, target, existingTarget);
        } else {
          SharedFolderMutationRecovery recovery =
              prepareRecovery(account, effectiveRequest, source, existingTarget);
          afterPhysicalMutationTransition(SharedFolderMutationRecoveryState.PREPARED);
          replaceThroughDurableQuarantine(recovery, source, target, existingTarget);
        }
      } else {
        moveAtomically(source, target, false);
      }
      return describe(targetRelative, target);
    } catch (FileAlreadyExistsException exception) {
      throw conflict();
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (IOException | SecurityException exception) {
      throw notFound();
    }
  }

  /** Physically deletes an observed item until the later recycle layer replaces this seam. */
  public void delete(SharedFolderDeleteRequest request) {
    Account account = access.requireWrite();
    requireMutationCapacity(account);
    validateDelete(request);
    if (!properties.enabled()) {
      throw unavailable();
    }
    requireRetainedVisibleMutationBoundary();
    reconcileOwner(account);
    if (nativeBoundary.nativeMode()) {
      try {
        NativeFileMetadata observed = nativeBoundary.metadata(request.path());
        requireNativeToken(request.path(), observed, request.observedToken());
        nativeBoundary.delete(request.path(), observed);
        return;
      } catch (ResponseStatusException exception) {
        throw exception;
      } catch (NativeBoundaryException exception) {
        throw nativeFailure(exception);
      } catch (SecurityException exception) {
        throw unavailable();
      }
    }
    requirePortableBoundary();
    try {
      SharedFolderPathResolver resolver = portableResolver();
      Path source = resolver.existing(request.path());
      requireCurrentToken(resolver, request.path(), source, request.observedToken());
      resolver.recheckForMutation(source);
      requireCurrentToken(resolver, request.path(), source, request.observedToken());
      Files.delete(source);
    } catch (DirectoryNotEmptyException exception) {
      throw conflict();
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (IOException | SecurityException exception) {
      throw notFound();
    }
  }

  private void requireCurrentToken(
      SharedFolderPathResolver resolver, String relative, Path path, String suppliedToken) {
    if (!SharedFolderObservedItemTokens.matches(suppliedToken, currentToken(resolver, relative, path))) {
      throw conflict();
    }
  }

  /** Supplies the portable resolver; controlled providers can provide equivalent mount facts. */
  protected SharedFolderPathResolver portableResolver() {
    return new SharedFolderPathResolver(properties.root());
  }

  private String currentToken(SharedFolderPathResolver resolver, String relative, Path path) {
    try {
      return SharedFolderObservedItemTokens.token(relative, resolver.readHandle(path).attributes());
    } catch (SecurityException exception) {
      throw notFound();
    }
  }

  private SharedDirectoryEntry describe(String relative, Path target) throws IOException {
    BasicFileAttributes attributes = Files.readAttributes(target, BasicFileAttributes.class,
        LinkOption.NOFOLLOW_LINKS);
    SharedDirectoryEntryType type = attributes.isDirectory()
        ? SharedDirectoryEntryType.DIRECTORY : SharedDirectoryEntryType.FILE;
    return new SharedDirectoryEntry(
        target.getFileName().toString(), relative, type, attributes.isRegularFile() ? attributes.size() : 0,
        attributes.lastModifiedTime().toInstant(),
        attributes.isRegularFile() ? SharedFolderContentPolicy.previewKind(target.getFileName().toString())
            : SharedFolderPreviewKind.NONE,
        SharedFolderObservedItemTokens.token(relative, attributes));
  }

  private SharedDirectoryEntry describeNative(String relative, NativeFileMetadata metadata) {
    String name = relative.substring(relative.lastIndexOf('/') + 1);
    SharedDirectoryEntryType type = metadata.directory()
        ? SharedDirectoryEntryType.DIRECTORY : SharedDirectoryEntryType.FILE;
    return new SharedDirectoryEntry(
        name, relative, type, metadata.regularFile() ? metadata.size() : 0, metadata.modifiedAt(),
        metadata.regularFile() ? SharedFolderContentPolicy.previewKind(name)
            : SharedFolderPreviewKind.NONE,
        nativeToken(relative, metadata));
  }

  private void requireNativeToken(
      String relative, NativeFileMetadata metadata, String suppliedToken) {
    if (!SharedFolderObservedItemTokens.matches(suppliedToken, nativeToken(relative, metadata))) {
      throw conflict();
    }
  }

  private String nativeToken(String relative, NativeFileMetadata metadata) {
    String identity = metadata.identity().volumeSerial() + ":"
        + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(metadata.identity().fileId());
    return SharedFolderObservedItemTokens.token(
        relative, identity, metadata.directory(), metadata.size(), metadata.modifiedAt());
  }

  private void moveCaseOnly(Path source, Path target, SharedFolderPathResolver resolver) throws IOException {
    resolver.recheckForMutation(source.getParent());
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS) || !Files.isSameFile(source, target)) {
      throw conflict();
    }
    moveCaseOnlyAtomically(source, target);
  }

  /** Performs one provider-guaranteed atomic case-only rename or leaves the source unchanged. */
  protected void moveCaseOnlyAtomically(Path source, Path target) throws IOException {
    try {
      Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException exception) {
      throw conflict();
    }
  }

  protected void moveAtomically(Path source, Path target, boolean replace) throws IOException {
    try {
      if (replace) {
        Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      } else {
        // ATOMIC_MOVE may replace an existing target even without REPLACE_EXISTING on Windows.
        // The ordinary same-volume move preserves the explicit no-replacement contract.
        beforePortableNoReplaceMove(source, target);
        Files.move(source, target);
      }
    } catch (AtomicMoveNotSupportedException exception) {
      throw conflict();
    }
  }

  /** Test seam immediately before the provider's strict no-replacement move. */
  protected void beforePortableNoReplaceMove(Path source, Path target) throws IOException { }

  private void replaceThroughQuarantine(Path source, Path target, Path observedTarget)
      throws IOException {
    if (Files.isDirectory(observedTarget, LinkOption.NOFOLLOW_LINKS)) {
      try (var children = Files.list(observedTarget)) {
        if (children.findAny().isPresent()) {
          throw conflict();
        }
      }
    }
    String quarantineKey = UUID.randomUUID().toString();
    requireMutationQuarantineSameFileStore(source);
    privateMoveInto(quarantineKey, observedTarget);
    try {
      moveAtomically(source, target, false);
    } catch (IOException | RuntimeException failure) {
      try {
        privateMoveOut(quarantineKey, target);
      } catch (IOException | RuntimeException restoreFailure) {
        failure.addSuppressed(restoreFailure);
      }
      throw failure;
    }
    try {
      deleteMutationQuarantine(quarantineKey);
    } catch (DirectoryNotEmptyException exception) {
      throw conflict();
    }
  }

  private SharedFolderMutationRecovery prepareRecovery(
      Account account, SharedFolderMoveRequest request, Path source, Path target) throws IOException {
    if (account == null || account.getId() == null || account.getId().isBlank()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Shared-folder write access is required");
    }
    Instant now = leaseNow();
    SharedFolderMutationRecovery recovery = new SharedFolderMutationRecovery();
    recovery.setId(UUID.randomUUID().toString());
    recovery.setOwnerId(account.getId());
    recovery.setSourcePath(request.path());
    recovery.setDestinationParentPath(request.destinationPath());
    recovery.setName(request.name());
    recovery.setSourceIdentity(portableStableIdentity(source));
    recovery.setTargetIdentity(portableReplacementIdentity(target));
    recovery.setQuarantineKey(UUID.randomUUID().toString());
    recovery.setNativeMode(false);
    recovery.setState(SharedFolderMutationRecoveryState.PREPARED);
    assignOperationLease(recovery, now);
    recovery.setCreatedAt(now);
    recovery.setUpdatedAt(now);
    return recoveries.save(recovery);
  }

  private SharedFolderMutationRecovery prepareNativeRecovery(
      Account account, SharedFolderMoveRequest request,
      NativeFileMetadata source, NativeFileMetadata target) {
    if (account == null || account.getId() == null || account.getId().isBlank()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Shared-folder write access is required");
    }
    Instant now = leaseNow();
    SharedFolderMutationRecovery recovery = new SharedFolderMutationRecovery();
    recovery.setId(UUID.randomUUID().toString());
    recovery.setOwnerId(account.getId());
    recovery.setSourcePath(request.path());
    recovery.setDestinationParentPath(request.destinationPath());
    recovery.setName(request.name());
    recovery.setSourceIdentity(nativeStableIdentity(source));
    recovery.setTargetIdentity(nativeStableIdentity(target));
    recovery.setQuarantineKey(UUID.randomUUID().toString());
    recovery.setNativeMode(true);
    recovery.setState(SharedFolderMutationRecoveryState.PREPARED);
    assignOperationLease(recovery, now);
    recovery.setCreatedAt(now);
    recovery.setUpdatedAt(now);
    return recoveries.save(recovery);
  }

  private NativeFileMetadata replaceNativeThroughDurableQuarantine(
      SharedFolderMutationRecovery recovery,
      NativeFileMetadata expectedSource,
      NativeFileMetadata expectedTarget) {
    NativeFileMetadata quarantined;
    try {
      renewOperationLease(recovery);
      quarantined = nativeBoundary.quarantineVisible(
          relativePath(recovery.getDestinationParentPath(), recovery.getName()),
          recovery.getQuarantineKey(), expectedTarget);
    } catch (NativeBoundaryException exception) {
      if (isNativeMissing(exception)) {
        throw conflict();
      }
      throw exception;
    }
    if (!java.util.Objects.equals(
        recovery.getTargetIdentity(), nativeStableIdentity(quarantined))) {
      throw conflict();
    }
    afterPhysicalMutationTransition(SharedFolderMutationRecoveryState.TARGET_QUARANTINED);
    renewOperationLease(recovery);
    recovery.setState(SharedFolderMutationRecoveryState.TARGET_QUARANTINED);
    recovery.setUpdatedAt(leaseNow());
    recovery = recoveries.save(recovery);
    try {
      renewOperationLease(recovery);
      NativeFileMetadata moved = nativeBoundary.moveVisibleNoReplace(
          recovery.getSourcePath(), recovery.getDestinationParentPath(), recovery.getName(),
          expectedSource);
      afterPhysicalMutationTransition(SharedFolderMutationRecoveryState.SOURCE_MOVED);
      renewOperationLease(recovery);
      recovery.setState(SharedFolderMutationRecoveryState.SOURCE_MOVED);
      recovery.setUpdatedAt(leaseNow());
      recovery = recoveries.save(recovery);
      renewOperationLease(recovery);
      nativeBoundary.deleteQuarantine(recovery.getQuarantineKey(), quarantined);
      recoveries.deleteById(recovery.getId());
      return moved;
    } catch (OperationLeaseLostException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      renewOperationLease(recovery);
      recovery.setState(SharedFolderMutationRecoveryState.RESTORE_PENDING);
      recovery.setUpdatedAt(leaseNow());
      recoveries.save(recovery);
      reconcileClaimedRecovery(recovery);
      throw failure;
    }
  }

  private void replaceThroughDurableQuarantine(
      SharedFolderMutationRecovery recovery, Path source, Path target, Path observedTarget)
      throws IOException {
    requireMutationQuarantineSameFileStore(source);
    if (!portableReplacementIdentityMatches(
        observedTarget, recovery.getTargetIdentity(), recovery)) {
      recoveries.deleteById(recovery.getId());
      throw conflict();
    }
    renewOperationLease(recovery);
    privateMoveInto(recovery.getQuarantineKey(), observedTarget);
    try {
      if (!quarantineIdentityMatches(recovery)) {
        throw conflict();
      }
      afterPhysicalMutationTransition(SharedFolderMutationRecoveryState.TARGET_QUARANTINED);
      if (!quarantineIdentityMatches(recovery)) {
        throw conflict();
      }
      renewOperationLease(recovery);
      recovery.setState(SharedFolderMutationRecoveryState.TARGET_QUARANTINED);
      recovery.setUpdatedAt(leaseNow());
      recovery = recoveries.save(recovery);
      renewOperationLease(recovery);
      moveAtomically(source, target, false);
      afterPhysicalMutationTransition(SharedFolderMutationRecoveryState.SOURCE_MOVED);
      renewOperationLease(recovery);
      recovery.setState(SharedFolderMutationRecoveryState.SOURCE_MOVED);
      recovery.setUpdatedAt(leaseNow());
      recovery = recoveries.save(recovery);
      renewOperationLease(recovery);
      deleteMutationQuarantine(recovery.getQuarantineKey());
      recoveries.deleteById(recovery.getId());
    } catch (OperationLeaseLostException failure) {
      throw failure;
    } catch (IOException | RuntimeException failure) {
      renewOperationLease(recovery);
      recovery.setState(SharedFolderMutationRecoveryState.RESTORE_PENDING);
      recovery.setUpdatedAt(leaseNow());
      recoveries.save(recovery);
      restoreOwnedPortableReplacement(recovery, source, target);
      throw failure;
    }
  }

  private void restoreOwnedPortableReplacement(
      SharedFolderMutationRecovery recovery, Path source, Path target) {
    try {
      if (identityMatches(target, recovery.getSourceIdentity())) {
        reconcileClaimedRecovery(recovery);
        return;
      }
      if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)
          && Files.exists(source, LinkOption.NOFOLLOW_LINKS)
          && mutationQuarantineExists(recovery.getQuarantineKey())) {
        renewOperationLease(recovery);
        restoreMutationQuarantine(recovery.getQuarantineKey(), target);
        recoveries.deleteById(recovery.getId());
        return;
      }
      reconcileClaimedRecovery(recovery);
    } catch (OperationLeaseLostException leaseLost) {
      throw leaseLost;
    } catch (IOException | RuntimeException restoreFailure) {
      // The durable RESTORE_PENDING record retains every path and private key for later recovery.
    }
  }

  /** Testable crash boundary immediately after a physical journal transition. */
  protected void afterPhysicalMutationTransition(SharedFolderMutationRecoveryState state) {
    // Production process death occurs outside Java control; tests inject it at the same boundary.
  }

  /** Reconciles a bounded oldest-first journal batch once native roots are initialized. */
  @EventListener(ApplicationReadyEvent.class)
  public void reconcileStartup() {
    if (recoveries == null
        || !nativeBoundary.nativeMode() && !nativeBoundary.testOnlyPortableMode()) {
      return;
    }
    for (SharedFolderMutationRecovery recovery : recoveries.findTop100ByOrderByUpdatedAtAsc()) {
      reconcileRecovery(recovery);
    }
  }

  private void reconcileOwner(Account account) {
    if (recoveries == null || account == null || account.getId() == null || account.getId().isBlank()) {
      return;
    }
    for (SharedFolderMutationRecovery recovery
        : recoveries.findTop100ByOwnerIdOrderByUpdatedAtAsc(account.getId())) {
      reconcileRecovery(recovery);
    }
  }

  private void reconcileRecovery(SharedFolderMutationRecovery recovery) {
    if (!recovery.isNativeMode() && !nativeBoundary.testOnlyPortableMode()) {
      return;
    }
    SharedFolderMutationRecovery claimed = claimExpiredRecovery(recovery);
    if (claimed == null) {
      return;
    }
    reconcileClaimedRecovery(claimed);
  }

  private void reconcileClaimedRecovery(SharedFolderMutationRecovery recovery) {
    if (recovery.isNativeMode()) {
      reconcileNativeRecovery(recovery);
      return;
    }
    Path root = properties.root().toAbsolutePath().normalize();
    Path source = root.resolve(recovery.getSourcePath()).normalize();
    Path target = root.resolve(relativePath(
        recovery.getDestinationParentPath(), recovery.getName())).normalize();
    try {
      boolean quarantineMatches = quarantineIdentityMatches(recovery);
      renewOperationLease(recovery);
      boolean sourceAtTarget = identityMatches(target, recovery.getSourceIdentity());
      boolean targetOriginal = portableReplacementIdentityMatches(
          target, recovery.getTargetIdentity(), recovery);
      if (sourceAtTarget) {
        if (quarantineMatches) {
          renewOperationLease(recovery);
          deleteMutationQuarantine(recovery.getQuarantineKey());
        }
        recoveries.deleteById(recovery.getId());
        return;
      }
      if (targetOriginal && !mutationQuarantineExists(recovery.getQuarantineKey())) {
        recoveries.deleteById(recovery.getId());
        return;
      }
      if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)
          && quarantineMatches && identityMatches(source, recovery.getSourceIdentity())) {
        renewOperationLease(recovery);
        restoreMutationQuarantine(recovery.getQuarantineKey(), target);
        recoveries.deleteById(recovery.getId());
        return;
      }
      renewOperationLease(recovery);
      recovery.setState(SharedFolderMutationRecoveryState.RESTORE_PENDING);
      recovery.setUpdatedAt(leaseNow());
      recoveries.save(recovery);
    } catch (IOException | SecurityException exception) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Shared-folder replacement recovery is pending");
    }
  }

  private SharedFolderMutationRecovery claimExpiredRecovery(
      SharedFolderMutationRecovery candidate) {
    Instant now = leaseNow();
    if (candidate.getOperationLeaseExpiresAt() != null
        && candidate.getOperationLeaseExpiresAt().isAfter(now)) {
      return null;
    }
    String recoveryToken = serviceInstanceId + ":recovery:" + UUID.randomUUID();
    Instant recoveryExpiry = now.plus(operationLeaseDuration());
    long claimed = recoveries.claimExpiredOperationLease(
        candidate.getId(), candidate.getOperationLeaseToken(), candidate.getState(), now,
        recoveryToken, recoveryExpiry, now);
    if (claimed != 1L) {
      return null;
    }
    return recoveries.findById(candidate.getId())
        .filter(current -> java.util.Objects.equals(
            recoveryToken, current.getOperationLeaseToken()))
        .orElse(null);
  }

  private void assignOperationLease(SharedFolderMutationRecovery recovery, Instant now) {
    recovery.setOperationLeaseToken(serviceInstanceId + ":operation:" + UUID.randomUUID());
    recovery.setOperationLeaseExpiresAt(now.plus(operationLeaseDuration()));
  }

  private void renewOperationLease(SharedFolderMutationRecovery recovery) {
    Instant now = leaseNow();
    Instant expiresAt = now.plus(operationLeaseDuration());
    long renewed = recoveries.renewOperationLease(
        recovery.getId(), recovery.getOperationLeaseToken(), recovery.getState(), expiresAt, now);
    if (renewed != 1L) {
      throw new OperationLeaseLostException();
    }
    recovery.setOperationLeaseExpiresAt(expiresAt);
    recovery.setUpdatedAt(now);
  }

  /** Test seam for deterministic short leases. */
  protected Instant leaseNow() { return Instant.now(); }

  /** Test seam for deterministic short leases. */
  protected java.time.Duration operationLeaseDuration() { return OPERATION_LEASE_TTL; }

  private java.time.Duration operationLeaseRenewalInterval() {
    java.time.Duration interval = operationLeaseDuration().dividedBy(3);
    return interval.isZero() ? java.time.Duration.ofMillis(1) : interval;
  }

  private void reconcileNativeRecovery(SharedFolderMutationRecovery recovery) {
    String targetPath = relativePath(recovery.getDestinationParentPath(), recovery.getName());
    renewOperationLease(recovery);
    NativeFileMetadata source = nativeMetadataOrNull(recovery.getSourcePath());
    renewOperationLease(recovery);
    NativeFileMetadata target = nativeMetadataOrNull(targetPath);
    renewOperationLease(recovery);
    NativeFileMetadata quarantine = nativeQuarantineMetadataOrNull(recovery.getQuarantineKey());
    boolean sourceOriginal = nativeIdentityMatches(source, recovery.getSourceIdentity());
    boolean sourceAtTarget = nativeIdentityMatches(target, recovery.getSourceIdentity());
    boolean targetOriginal = nativeIdentityMatches(target, recovery.getTargetIdentity());
    boolean quarantineOriginal = nativeIdentityMatches(quarantine, recovery.getTargetIdentity());
    try {
      if (sourceAtTarget) {
        if (quarantineOriginal) {
          renewOperationLease(recovery);
          nativeBoundary.deleteQuarantine(recovery.getQuarantineKey(), quarantine);
        }
        recoveries.deleteById(recovery.getId());
        return;
      }
      if (targetOriginal && quarantine == null) {
        recoveries.deleteById(recovery.getId());
        return;
      }
      if (target == null && quarantineOriginal && sourceOriginal) {
        renewOperationLease(recovery);
        nativeBoundary.restoreQuarantine(
            recovery.getQuarantineKey(), recovery.getDestinationParentPath(), recovery.getName(),
            quarantine);
        recoveries.deleteById(recovery.getId());
        return;
      }
      renewOperationLease(recovery);
      recovery.setState(SharedFolderMutationRecoveryState.RESTORE_PENDING);
      recovery.setUpdatedAt(leaseNow());
      recoveries.save(recovery);
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (NativeBoundaryException exception) {
      throw nativeFailure(exception);
    } catch (SecurityException exception) {
      throw unavailable();
    } catch (RuntimeException exception) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Shared-folder replacement recovery is pending");
    }
  }

  private NativeFileMetadata nativeMetadataOrNull(String path) {
    try {
      return nativeBoundary.metadata(path);
    } catch (NativeBoundaryException exception) {
      if (isNativeMissing(exception)) return null;
      throw nativeFailure(exception);
    } catch (SecurityException exception) {
      throw unavailable();
    }
  }

  private NativeFileMetadata nativeQuarantineMetadataOrNull(String key) {
    try {
      return nativeBoundary.quarantineMetadata(key);
    } catch (NativeBoundaryException exception) {
      if (isNativeMissing(exception)) return null;
      throw nativeFailure(exception);
    } catch (SecurityException exception) {
      throw unavailable();
    }
  }

  private boolean nativeIdentityMatches(NativeFileMetadata metadata, String expected) {
    return metadata != null && java.util.Objects.equals(expected, nativeStableIdentity(metadata));
  }

  private String nativeStableIdentity(NativeFileMetadata metadata) {
    return metadata.identity().volumeSerial() + ":"
        + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(metadata.identity().fileId())
        + ":" + metadata.directory();
  }

  private void requireMutationQuarantineSameFileStore(Path other) throws IOException {
    try {
      if (!privateBoundary.directorySharesFileStore(
          "shared-folder-mutation-quarantine", other)) {
        throw conflict();
      }
    } catch (BoundaryUnavailableException exception) {
      throw unavailable();
    }
  }

  private void privateMoveInto(String key, Path source) throws IOException {
    try {
      privateBoundary.moveInto("shared-folder-mutation-quarantine", key, source);
    } catch (BoundaryUnavailableException exception) {
      throw unavailable();
    }
  }

  private void privateMoveOut(String key, Path target) throws IOException {
    try {
      privateBoundary.moveOut("shared-folder-mutation-quarantine", key, target);
    } catch (BoundaryUnavailableException exception) {
      throw unavailable();
    }
  }

  private boolean quarantineIdentityMatches(SharedFolderMutationRecovery recovery)
      throws IOException {
    return java.util.Objects.equals(recovery.getTargetIdentity(),
        privateReplacementIdentity(recovery.getQuarantineKey(), recovery));
  }

  private boolean mutationQuarantineExists(String key) throws IOException {
    try {
      return privateBoundary.exists("shared-folder-mutation-quarantine", key);
    } catch (BoundaryUnavailableException exception) {
      throw unavailable();
    }
  }

  private void deleteMutationQuarantine(String key) throws IOException {
    try {
      privateBoundary.delete("shared-folder-mutation-quarantine", key);
    } catch (BoundaryUnavailableException exception) {
      throw unavailable();
    }
  }

  private void restoreMutationQuarantine(String key, Path target) throws IOException {
    privateMoveOut(key, target);
  }

  private boolean identityMatches(Path path, String expected) throws IOException {
    return Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        && java.util.Objects.equals(expected, portableStableIdentity(path));
  }

  private boolean portableReplacementIdentityMatches(Path path, String expected) throws IOException {
    return Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        && java.util.Objects.equals(expected, portableReplacementIdentity(path));
  }

  private boolean portableReplacementIdentityMatches(
      Path path, String expected, SharedFolderMutationRecovery recovery) throws IOException {
    return Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        && java.util.Objects.equals(expected, portableReplacementIdentity(path, recovery));
  }

  private String portableReplacementIdentity(Path path) throws IOException {
    return portableReplacementIdentity(path, null);
  }

  private String portableReplacementIdentity(
      Path path, SharedFolderMutationRecovery recovery) throws IOException {
    Runnable heartbeat = recovery == null ? () -> { } : () -> renewOperationLease(recovery);
    heartbeat.run();
    BasicFileAttributes before = Files.readAttributes(
        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    String metadata = portableStableIdentity(path) + ":" + before.size() + ":"
        + before.lastModifiedTime().toMillis();
    if (before.isDirectory()) {
      try (var children = Files.list(path)) {
        if (children.findAny().isPresent()) throw conflict();
      }
      heartbeat.run();
      return metadata + ":empty-directory";
    }
    if (!before.isRegularFile()) throw conflict();
    MessageDigest digest = sha256Digest();
    try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      byte[] buffer = new byte[16 * 1024];
      long sinceRenewal = 0;
      Instant lastRenewalAt = leaseNow();
      for (int count; (count = input.read(buffer)) != -1;) {
        digest.update(buffer, 0, count);
        sinceRenewal += count;
        Instant now = leaseNow();
        if (sinceRenewal >= LEASE_RENEWAL_BYTES
            || !now.isBefore(lastRenewalAt.plus(operationLeaseRenewalInterval()))) {
          heartbeat.run();
          sinceRenewal = 0;
          lastRenewalAt = now;
        }
      }
    }
    heartbeat.run();
    BasicFileAttributes after = Files.readAttributes(
        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (before.size() != after.size()
        || !before.lastModifiedTime().equals(after.lastModifiedTime())
        || !java.util.Objects.equals(before.fileKey(), after.fileKey())) {
      throw conflict();
    }
    return metadata + ":sha256:"
        + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
  }

  private String privateReplacementIdentity(
      String key, SharedFolderMutationRecovery recovery) throws IOException {
    Runnable heartbeat = recovery == null ? () -> { } : () -> renewOperationLease(recovery);
    heartbeat.run();
    var before = privateMetadata(key);
    BasicFileAttributes attributes = before.attributes();
    String metadata = privateStableIdentity(before) + ":" + attributes.size() + ":"
        + attributes.lastModifiedTime().toMillis();
    if (attributes.isDirectory()) {
      if (!privateDirectoryIsEmpty(key)) throw conflict();
      heartbeat.run();
      var after = privateMetadata(key);
      if (!java.util.Objects.equals(before.stableIdentity(), after.stableIdentity())) {
        throw conflict();
      }
      return metadata + ":empty-directory";
    }
    if (!attributes.isRegularFile()) throw conflict();
    MessageDigest digest = sha256Digest();
    try {
      privateBoundary.operateOnRegularFile(
          "shared-folder-mutation-quarantine", key, FileAccess.READ, channel -> {
        var input = Channels.newInputStream(channel);
        byte[] buffer = new byte[16 * 1024];
        long sinceRenewal = 0;
        Instant lastRenewalAt = leaseNow();
        for (int count; (count = input.read(buffer)) != -1;) {
          digest.update(buffer, 0, count);
          sinceRenewal += count;
          Instant now = leaseNow();
          if (sinceRenewal >= LEASE_RENEWAL_BYTES
              || !now.isBefore(lastRenewalAt.plus(operationLeaseRenewalInterval()))) {
            heartbeat.run();
            sinceRenewal = 0;
            lastRenewalAt = now;
          }
        }
        return null;
      });
    } catch (BoundaryUnavailableException exception) {
      throw unavailable();
    }
    heartbeat.run();
    var after = privateMetadata(key);
    if (!java.util.Objects.equals(before.stableIdentity(), after.stableIdentity())
        || attributes.size() != after.attributes().size()
        || !attributes.lastModifiedTime().equals(after.attributes().lastModifiedTime())) {
      throw conflict();
    }
    return metadata + ":sha256:"
        + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
  }

  private PortableSharedFolderPrivateBoundary.PrivateLeafMetadata privateMetadata(String key)
      throws IOException {
    try {
      return privateBoundary.metadata("shared-folder-mutation-quarantine", key);
    } catch (BoundaryUnavailableException exception) {
      throw unavailable();
    }
  }

  private boolean privateDirectoryIsEmpty(String key) throws IOException {
    try {
      return privateBoundary.directoryIsEmpty("shared-folder-mutation-quarantine", key);
    } catch (BoundaryUnavailableException exception) {
      throw unavailable();
    }
  }

  private MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required", exception);
    }
  }

  private void requirePortableReplacementTarget(Path target) throws IOException {
    portableReplacementIdentity(target);
  }

  private String portableStableIdentity(Path path) throws IOException {
    BasicFileAttributes attributes = Files.readAttributes(
        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    return dev.christopherbell.sharedfolder.fs.PortableSharedFolderPrivateBoundary
        .stableIdentity(path) + ":" + attributes.isDirectory();
  }

  private String privateStableIdentity(
      dev.christopherbell.sharedfolder.fs.PortableSharedFolderPrivateBoundary.PrivateLeafMetadata
          metadata) {
    return metadata.stableIdentity() + ":" + metadata.attributes().isDirectory();
  }

  private void requireSameFileStore(Path first, Path second) throws IOException {
    if (!Files.getFileStore(first).equals(Files.getFileStore(second))) {
      throw conflict();
    }
  }

  private void requireRetainedVisibleMutationBoundary() {
    if (!nativeBoundary.nativeMode() && !nativeBoundary.testOnlyPortableMode()) {
      throw unavailable();
    }
  }

  private void requireMutationCapacity(Account account) {
    if (mutationLimiter != null) mutationLimiter.requireMutation(account);
  }

  private void requirePortableBoundary() {
    Path root = properties.root().toAbsolutePath().normalize();
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
      throw unavailable();
    }
  }

  private String parentPath(String path) {
    int separator = path == null ? -1 : path.lastIndexOf('/');
    return separator < 0 ? "" : path.substring(0, separator);
  }

  private void validateCreateFolder(SharedFolderCreateFolderRequest request) {
    if (request == null) {
      throw invalid();
    }
    validateRelativePath(request.parentPath(), true);
    validateName(request.name());
  }

  private void validateRename(SharedFolderRenameRequest request) {
    if (request == null) {
      throw invalid();
    }
    validateRelativePath(request.path(), false);
    validateName(request.name());
    validateToken(request.observedToken());
  }

  private void validateMove(SharedFolderMoveRequest request) {
    if (request == null) {
      throw invalid();
    }
    validateRelativePath(request.path(), false);
    validateRelativePath(request.destinationPath(), true);
    validateName(request.name());
    validateToken(request.observedToken());
    if (request.replace()) {
      validateToken(request.replacedObservedToken());
    } else if (request.replacedObservedToken() != null && !request.replacedObservedToken().isBlank()) {
      throw invalid();
    }
  }

  private void validateDelete(SharedFolderDeleteRequest request) {
    if (request == null) {
      throw invalid();
    }
    validateRelativePath(request.path(), false);
    validateToken(request.observedToken());
  }

  private void validateRelativePath(String path, boolean allowEmpty) {
    try {
      SharedFolderPathResolver.safeRelativeSegments(path, allowEmpty);
    } catch (UnsafeSharedPathException exception) {
      throw invalid();
    }
  }

  private void validateName(String name) {
    try {
      SharedFolderPathResolver.validateSingleWindowsName(name);
    } catch (UnsafeSharedPathException exception) {
      throw invalid();
    }
  }

  private void validateToken(String token) {
    if (token == null || token.isBlank() || token.length() > 256) {
      throw invalid();
    }
  }

  private String relativePath(String parent, String name) {
    return parent == null || parent.isEmpty() ? name : parent + "/" + name;
  }

  private ResponseStatusException conflict() {
    return new ResponseStatusException(HttpStatus.CONFLICT, "Shared-folder target already exists");
  }

  private static final class OperationLeaseLostException extends ResponseStatusException {
    private OperationLeaseLostException() {
      super(HttpStatus.CONFLICT, "Shared-folder mutation lease was lost");
    }
  }

  private ResponseStatusException invalid() {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Shared-folder request is invalid");
  }

  private ResponseStatusException unavailable() {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
        "Shared-folder boundary is unavailable");
  }

  private ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Shared-folder item was not found");
  }

  private ResponseStatusException nativeFailure(NativeBoundaryException exception) {
    if (exception.kind() == NativeBoundaryException.Kind.CONFLICT) {
      return conflict();
    }
    if (exception.kind() == NativeBoundaryException.Kind.UNAVAILABLE) {
      return unavailable();
    }
    int status = exception.ntStatus();
    if (isNativeMissing(exception)) {
      return notFound();
    }
    if (status == 0xC0000035 || status == 0xC0000043 || status == 32
        || status == 80 || status == 183 || status == 0xC0000101) {
      return conflict();
    }
    return unavailable();
  }

  private boolean isNativeMissing(NativeBoundaryException exception) {
    int status = exception.ntStatus();
    return status == 0xC0000034 || status == 0xC000003A || status == 2 || status == 3;
  }

}
