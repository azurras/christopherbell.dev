package dev.christopherbell.sharedfolder.recycle;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.sharedfolder.fs.PortableSharedFolderPrivateBoundary;
import dev.christopherbell.sharedfolder.fs.SharedFolderPathResolver;
import dev.christopherbell.sharedfolder.fs.UnsafeSharedPathException;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderMutationBoundary;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeBoundaryException;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeFileMetadata;
import dev.christopherbell.sharedfolder.model.SharedDirectoryEntry;
import dev.christopherbell.sharedfolder.model.SharedDirectoryEntryType;
import dev.christopherbell.sharedfolder.model.SharedFolderDeleteRequest;
import dev.christopherbell.sharedfolder.model.SharedFolderPreviewKind;
import dev.christopherbell.sharedfolder.security.SharedFolderAccessService;
import dev.christopherbell.sharedfolder.service.SharedFolderObservedItemTokens;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRecorder;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Moves observed visible items into isolated private retention and administers restore/purge. */
@Service
@Slf4j
public class SharedFolderRecycleService {
  private static final String RECYCLE_DIRECTORY = "shared-folder-recycle";
  private static final String REPLACED_DIRECTORY = "shared-folder-recycle-replaced";
  private static final int ADMIN_LIST_LIMIT = 200;
  private static final int MAX_ADMIN_LIST_PAGE = 10_000;
  private static final int CLEANUP_BATCH_LIMIT = 100;
  private static final int RECOVERY_BATCH_LIMIT = 100;
  private static final Duration MAINTENANCE_RETRY_DELAY = Duration.ofDays(1);

  private final SharedFolderAccessService access;
  private final SharedFolderProperties properties;
  private final WindowsSharedFolderMutationBoundary nativeBoundary;
  private final SharedFolderRecycleRepository repository;
  private final Clock clock;
  private final PortableSharedFolderPrivateBoundary privateBoundary;
  private final SharedFolderAuditRecorder audit;

  public SharedFolderRecycleService(
      SharedFolderAccessService access,
      SharedFolderProperties properties,
      WindowsSharedFolderMutationBoundary nativeBoundary,
      SharedFolderRecycleRepository repository,
      Clock clock) {
    this(access, properties, nativeBoundary, repository, clock, null);
  }

  @Autowired
  public SharedFolderRecycleService(
      SharedFolderAccessService access,
      SharedFolderProperties properties,
      WindowsSharedFolderMutationBoundary nativeBoundary,
      SharedFolderRecycleRepository repository,
      Clock clock,
      SharedFolderAuditRecorder audit) {
    this.access = access;
    this.properties = properties;
    this.nativeBoundary = nativeBoundary;
    this.repository = repository;
    this.clock = clock;
    this.audit = audit;
    this.privateBoundary = nativeBoundary.testOnlyPortableMode()
        ? PortableSharedFolderPrivateBoundary.testOnlyWithPathMoves(properties.systemRoot())
        : new PortableSharedFolderPrivateBoundary(properties.systemRoot());
  }

  /** Replaces physical delete with a durable private move of the exact observed item. */
  public synchronized SharedFolderRecycleItem recycle(SharedFolderDeleteRequest request) {
    Account account = access.requireWrite();
    requireEnabled();
    if (request == null || request.path() == null || request.observedToken() == null) {
      throw badRequest();
    }
    try {
      SharedFolderPathResolver.safeRelativeSegments(request.path(), false);
    } catch (UnsafeSharedPathException exception) {
      throw badRequest();
    }
    if (!nativeBoundary.nativeMode() && !nativeBoundary.testOnlyPortableMode()) {
      throw unavailable();
    }
    Instant now = clock.instant();
    String id = UUID.randomUUID().toString();
    String payloadKey = UUID.randomUUID().toString();
    if (nativeBoundary.nativeMode()) {
      return recycleNative(request, account, now, id, payloadKey);
    }
    try {
      SharedFolderPathResolver resolver = new SharedFolderPathResolver(properties.root());
      Path source = resolver.existing(request.path());
      BasicFileAttributes attributes = resolver.readHandle(source).attributes();
      String current = SharedFolderObservedItemTokens.token(request.path(), attributes);
      if (!SharedFolderObservedItemTokens.matches(request.observedToken(), current)) {
        throw conflict();
      }
      resolver.recheckForMutation(source);
      BasicFileAttributes rechecked = resolver.readHandle(source).attributes();
      String finalToken = SharedFolderObservedItemTokens.token(request.path(), rechecked);
      if (!SharedFolderObservedItemTokens.matches(request.observedToken(), finalToken)) {
        throw conflict();
      }
      SharedFolderRecycleItem item = preparing(
          id, request.path(), account, now, payloadKey, rechecked.size(), rechecked.isDirectory(),
          finalToken, portableIdentity(source, rechecked));
      repository.save(item);
      privateBoundary.moveInto(RECYCLE_DIRECTORY, payloadKey, source);
      SharedFolderRecycleItem recycled = repository.save(
          item.withState(SharedFolderRecycleState.RECYCLED));
      recordFor(account, "RECYCLE", recycled.originalPath(), recycled.size());
      return recycled;
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (UnsafeSharedPathException exception) {
      throw notFound();
    } catch (IOException | SecurityException exception) {
      throw unavailable();
    }
  }

  /** Lists current recoverable entries for a freshly authorized administrator. */
  public List<SharedFolderRecycleItem> list() {
    return listPage(0).items();
  }

  /** Lists one bounded page so administrators can reach every recoverable entry. */
  public SharedFolderRecyclePage listPage(int page) {
    access.requireAdmin();
    requireEnabled();
    if (page < 0 || page > MAX_ADMIN_LIST_PAGE) throw badRequest();
    var result = repository.findByStateOrderByDeletedAtDescIdDesc(
        SharedFolderRecycleState.RECYCLED, PageRequest.of(page, ADMIN_LIST_LIMIT));
    return new SharedFolderRecyclePage(
        result.getContent(), page, page < MAX_ADMIN_LIST_PAGE && result.hasNext());
  }

  /** Restores to the original path, replacing only when the administrator explicitly requested it. */
  public synchronized SharedDirectoryEntry restore(String id, boolean replace) {
    Account admin = access.requireAdmin();
    requireEnabled();
    SharedFolderRecycleItem item = current(id);
    if (!item.expiresAt().isAfter(clock.instant())) {
      throw notFound();
    }
    if (!nativeBoundary.nativeMode() && !nativeBoundary.testOnlyPortableMode()) {
      throw unavailable();
    }
    if (nativeBoundary.nativeMode()) {
      SharedDirectoryEntry restored = restoreNative(item, replace);
      recordFor(admin, "RESTORE", item.originalPath(), item.size());
      return restored;
    }
    try {
      SharedFolderPathResolver resolver = new SharedFolderPathResolver(properties.root());
      String parent = parentPath(item.originalPath());
      String name = leafName(item.originalPath());
      Path target = resolver.newChild(parent, name);
      if (!privateBoundary.exists(RECYCLE_DIRECTORY, item.payloadKey())) {
        throw notFound();
      }
      String replacementKey = null;
      String replacementFingerprint = null;
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        if (!replace) throw conflict();
        Path existing = resolver.existing(item.originalPath());
        resolver.recheckForMutation(existing);
        replacementKey = UUID.randomUUID().toString();
        replacementFingerprint = SharedFolderObservedItemTokens.token(
            item.originalPath(), resolver.readHandle(existing).attributes());
      }
      SharedFolderRecycleItem restoring = repository.save(
          item.withRestore(replacementKey, replacementFingerprint));
      try {
        if (replacementKey != null) {
          Path existing = resolver.existing(item.originalPath());
          privateBoundary.moveInto(REPLACED_DIRECTORY, replacementKey, existing);
          requirePortableFingerprint(
              restoring.replacementFingerprint(), REPLACED_DIRECTORY, replacementKey,
              item.originalPath());
        }
        privateBoundary.moveOut(RECYCLE_DIRECTORY, item.payloadKey(), target);
        if (replacementKey != null) {
          requirePortableFingerprint(
              restoring.replacementFingerprint(), REPLACED_DIRECTORY, replacementKey,
              item.originalPath());
          privateBoundary.delete(REPLACED_DIRECTORY, replacementKey);
        }
        repository.deleteById(item.id());
        SharedDirectoryEntry restored = describe(item.originalPath(), target);
        recordFor(admin, "RESTORE", item.originalPath(), item.size());
        return restored;
      } catch (IOException | RuntimeException failure) {
        try {
          reconcileItem(restoring);
        } catch (RuntimeException ignored) {
          // The durable RESTORING record remains for startup/scheduled reconciliation.
        }
        throw failure;
      }
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (FileAlreadyExistsException exception) {
      throw conflict();
    } catch (UnsafeSharedPathException exception) {
      throw notFound();
    } catch (IOException | SecurityException exception) {
      throw notFound();
    }
  }

  /** Permanently purges one retained payload after exact typed administrator confirmation. */
  public synchronized void purge(String id, String confirmation) {
    Account admin = access.requireAdmin();
    String safeId = requiredId(id);
    if (!("PURGE " + safeId).equals(confirmation == null ? "" : confirmation)) {
      throw badRequest();
    }
    SharedFolderRecycleItem item = current(id);
    purgeInternal(item);
    recordFor(admin, "PURGE", item.originalPath(), item.size());
  }

  /** Purges only recycle entries whose configured retention deadline has elapsed. */
  @Scheduled(fixedDelayString = "${app.shared-folder.recycle-cleanup-delay:PT1H}")
  public synchronized int cleanupExpired() {
    if (!properties.enabled()) return 0;
    reconcilePending();
    int purged = 0;
    Instant now = clock.instant();
    List<SharedFolderRecycleItem> batch = repository
        .findByStateAndExpiresAtBeforeAndRetryAfterLessThanEqualOrderByExpiresAtAscIdAsc(
            SharedFolderRecycleState.RECYCLED, now, now,
            PageRequest.of(0, CLEANUP_BATCH_LIMIT));
    for (SharedFolderRecycleItem item : batch) {
      try {
        purgeInternal(item);
        if (audit != null) audit.recordSystem(
            "RETENTION_PURGE", item.originalPath(), item.size(), "accepted", null);
        purged++;
      } catch (RuntimeException failure) {
        deferMaintenance(item);
        if (audit != null) audit.recordSystemFailure(
            "RETENTION_PURGE", item.originalPath(), item.size(), failure);
        log.warn("Shared-folder retention purge deferred for item {}", item.id());
      }
    }
    return purged;
  }

  /** Reconciles durable intermediate states after process or persistence interruption. */
  @EventListener(ApplicationReadyEvent.class)
  public synchronized int reconcilePending() {
    if (!properties.enabled()) return 0;
    int reconciled = 0;
    Instant now = clock.instant();
    List<SharedFolderRecycleItem> batch = repository
        .findByStateInAndRetryAfterLessThanEqualOrderByDeletedAtAscIdAsc(List.of(
            SharedFolderRecycleState.PREPARING,
            SharedFolderRecycleState.RESTORING,
            SharedFolderRecycleState.PURGING), now, PageRequest.of(0, RECOVERY_BATCH_LIMIT));
    for (SharedFolderRecycleItem item : batch) {
      try {
        if (reconcileItem(item)) {
          reconciled++;
        } else {
          deferMaintenance(item);
        }
      } catch (RuntimeException failure) {
        deferMaintenance(item);
        if (audit != null) audit.recordSystemFailure(
            recoveryAction(item), item.originalPath(), item.size(), failure);
        log.warn("Shared-folder recycle reconciliation deferred for item {}", item.id());
      }
    }
    return reconciled;
  }

  private void deferMaintenance(SharedFolderRecycleItem item) {
    try {
      repository.save(item.withRetryAfter(clock.instant().plus(MAINTENANCE_RETRY_DELAY)));
    } catch (RuntimeException persistenceFailure) {
      log.warn("Shared-folder maintenance retry could not be deferred for item {}", item.id());
    }
  }

  private SharedFolderRecycleItem recycleNative(
      SharedFolderDeleteRequest request, Account account, Instant now, String id, String key) {
    try {
      NativeFileMetadata observed = nativeBoundary.metadata(request.path());
      String observedToken = nativeVisibleToken(request.path(), observed);
      if (!SharedFolderObservedItemTokens.matches(request.observedToken(), observedToken)) {
        throw conflict();
      }
      SharedFolderRecycleItem item = preparing(
          id, request.path(), account, now, key, observed.size(), observed.directory(),
          nativeFingerprint(observed), nativeStableIdentity(observed));
      repository.save(item);
      NativeFileMetadata moved = nativeBoundary.recycleVisible(request.path(), key, observed);
      if (!item.sourceFingerprint().equals(nativeFingerprint(moved))) {
        throw unavailable();
      }
      SharedFolderRecycleItem recycled = repository.save(
          item.withState(SharedFolderRecycleState.RECYCLED));
      recordFor(account, "RECYCLE", recycled.originalPath(), recycled.size());
      return recycled;
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (NativeBoundaryException exception) {
      throw nativeFailure(exception);
    } catch (SecurityException exception) {
      throw unavailable();
    }
  }

  private SharedDirectoryEntry restoreNative(SharedFolderRecycleItem item, boolean replace) {
    try {
      NativeFileMetadata payload = nativeBoundary.recycleMetadata(item.payloadKey());
      requireNativeFingerprint(item, payload);
      String parent = parentPath(item.originalPath());
      String name = leafName(item.originalPath());
      NativeFileMetadata target = null;
      try {
        target = nativeBoundary.metadata(item.originalPath());
      } catch (NativeBoundaryException missing) {
        if (!isNativeMissing(missing)) throw missing;
      }
      if (target != null && !replace) throw conflict();
      String replacementKey = target == null ? null : UUID.randomUUID().toString();
      String replacementFingerprint = target == null ? null : nativeFingerprint(target);
      SharedFolderRecycleItem restoring = repository.save(
          item.withRestore(replacementKey, replacementFingerprint));
      try {
        NativeFileMetadata restored = nativeBoundary.restoreRecycle(
            item.payloadKey(), parent, name, payload, target != null && replace, target,
            replacementKey);
        repository.deleteById(restoring.id());
        return describeNative(item.originalPath(), restored);
      } catch (RuntimeException failure) {
        try {
          reconcileItem(restoring);
        } catch (RuntimeException ignored) {
          // The durable RESTORING record remains for startup/scheduled reconciliation.
        }
        throw failure;
      }
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (NativeBoundaryException exception) {
      throw nativeFailure(exception);
    } catch (SecurityException exception) {
      throw unavailable();
    }
  }

  private void purgeInternal(SharedFolderRecycleItem item) {
    requireEnabled();
    if (!nativeBoundary.nativeMode() && !nativeBoundary.testOnlyPortableMode()) {
      throw unavailable();
    }
    try {
      if (nativeBoundary.nativeMode()) {
        NativeFileMetadata payload = nativeBoundary.recycleMetadata(item.payloadKey());
        requireNativeFingerprint(item, payload);
        SharedFolderRecycleItem purging = repository.save(
            item.withState(SharedFolderRecycleState.PURGING));
        nativeBoundary.deleteRecycleTree(purging.payloadKey(), payload);
        repository.deleteById(purging.id());
        return;
      }
      requirePortableFingerprint(
          item.sourceFingerprint(), RECYCLE_DIRECTORY, item.payloadKey(), item.originalPath());
      SharedFolderRecycleItem purging = repository.save(
          item.withState(SharedFolderRecycleState.PURGING));
      privateBoundary.deleteTreeIfExists(RECYCLE_DIRECTORY, purging.payloadKey());
      repository.deleteById(purging.id());
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (NativeBoundaryException exception) {
      throw nativeFailure(exception);
    } catch (IOException | SecurityException exception) {
      throw unavailable();
    }
  }

  private boolean reconcileItem(SharedFolderRecycleItem item) {
    try {
      boolean reconciled = nativeBoundary.nativeMode()
          ? reconcileNative(item) : reconcilePortable(item);
      if (reconciled && audit != null) {
        audit.recordSystem(
            recoveryAction(item), item.originalPath(), item.size(), "accepted", null);
      }
      return reconciled;
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (NativeBoundaryException exception) {
      throw nativeFailure(exception);
    } catch (IOException | SecurityException exception) {
      throw unavailable();
    }
  }

  private String recoveryAction(SharedFolderRecycleItem item) {
    return switch (item.state()) {
      case PREPARING -> "RECYCLE_RECOVERY";
      case RESTORING -> "RESTORE_RECOVERY";
      case PURGING -> "PURGE_RECOVERY";
      case RECYCLED -> "RECYCLE_RECOVERY";
    };
  }

  private boolean reconcilePortable(SharedFolderRecycleItem item) throws IOException {
    if (!nativeBoundary.testOnlyPortableMode()) throw unavailable();
    SharedFolderPathResolver resolver = new SharedFolderPathResolver(properties.root());
    Path target = resolver.newChild(parentPath(item.originalPath()), leafName(item.originalPath()));
    boolean visible = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
    boolean payload = privateBoundary.exists(RECYCLE_DIRECTORY, item.payloadKey());
    boolean replacement = item.replacementKey() != null
        && privateBoundary.exists(REPLACED_DIRECTORY, item.replacementKey());

    return switch (item.state()) {
      case PREPARING -> {
        if (payload && !visible) {
          requirePortableFingerprint(
              item.sourceFingerprint(), RECYCLE_DIRECTORY, item.payloadKey(), item.originalPath());
          repository.save(item.recycledAgain());
          yield true;
        }
        if (!payload && visible) {
          if (!portableIdentity(target, resolver.readHandle(target).attributes())
              .equals(item.sourceIdentity())) {
            yield false;
          }
          repository.deleteById(item.id());
          yield true;
        }
        yield false;
      }
      case RESTORING -> {
        if (payload) {
          requirePortableFingerprint(
              item.sourceFingerprint(), RECYCLE_DIRECTORY, item.payloadKey(), item.originalPath());
          if (replacement && !visible) {
            requirePortableFingerprint(item.replacementFingerprint(), REPLACED_DIRECTORY,
                item.replacementKey(), item.originalPath());
            privateBoundary.moveOut(REPLACED_DIRECTORY, item.replacementKey(), target);
            repository.save(item.recycledAgain());
            yield true;
          }
          if (!replacement) {
            if (item.replacementKey() != null) {
              if (!visible) yield false;
              String visibleFingerprint = SharedFolderObservedItemTokens.token(
                  item.originalPath(), resolver.readHandle(target).attributes());
              if (!item.replacementFingerprint().equals(visibleFingerprint)) yield false;
            }
            repository.save(item.recycledAgain());
            yield true;
          }
          yield false;
        }
        if (visible && portableIdentity(
            target, resolver.readHandle(target).attributes()).equals(item.sourceIdentity())) {
          if (replacement) {
            requirePortableFingerprint(item.replacementFingerprint(), REPLACED_DIRECTORY,
                item.replacementKey(), item.originalPath());
            privateBoundary.delete(REPLACED_DIRECTORY, item.replacementKey());
          }
          repository.deleteById(item.id());
          yield true;
        }
        yield false;
      }
      case PURGING -> {
        if (payload) {
          requirePortableIdentity(
              item.sourceIdentity(), RECYCLE_DIRECTORY, item.payloadKey());
          privateBoundary.deleteTree(RECYCLE_DIRECTORY, item.payloadKey());
        }
        repository.deleteById(item.id());
        yield true;
      }
      case RECYCLED -> false;
    };
  }

  private boolean reconcileNative(SharedFolderRecycleItem item) {
    NativeFileMetadata payload = nativeMetadataOrNull(item.payloadKey(), false);
    NativeFileMetadata visible = visibleNativeMetadataOrNull(item.originalPath());
    NativeFileMetadata replacement = item.replacementKey() == null ? null
        : nativeMetadataOrNull(item.replacementKey(), true);
    return switch (item.state()) {
      case PREPARING -> {
        if (payload != null && visible == null) {
          requireNativeFingerprint(item, payload);
          repository.save(item.recycledAgain());
          yield true;
        }
        if (payload == null && visible != null) {
          if (!nativeStableIdentity(visible).equals(item.sourceIdentity())) yield false;
          repository.deleteById(item.id());
          yield true;
        }
        yield false;
      }
      case RESTORING -> {
        if (payload != null) {
          requireNativeFingerprint(item, payload);
          if (replacement != null && visible == null) {
            requireNativeReplacementFingerprint(item, replacement);
            nativeBoundary.restoreRecycleReplacement(
                item.replacementKey(), parentPath(item.originalPath()),
                leafName(item.originalPath()), replacement);
            repository.save(item.recycledAgain());
            yield true;
          }
          if (replacement == null) {
            if (item.replacementKey() != null) {
              if (visible == null
                  || !item.replacementFingerprint().equals(nativeFingerprint(visible))) {
                yield false;
              }
            }
            repository.save(item.recycledAgain());
            yield true;
          }
          yield false;
        }
        if (visible != null && nativeStableIdentity(visible).equals(item.sourceIdentity())) {
          if (replacement != null) {
            requireNativeReplacementFingerprint(item, replacement);
            nativeBoundary.deleteRecycleReplacement(item.replacementKey(), replacement);
          }
          repository.deleteById(item.id());
          yield true;
        }
        yield false;
      }
      case PURGING -> {
        if (payload != null) {
          requireNativeIdentity(item, payload);
          nativeBoundary.deleteRecycleTree(item.payloadKey(), payload);
        }
        repository.deleteById(item.id());
        yield true;
      }
      case RECYCLED -> false;
    };
  }

  private NativeFileMetadata nativeMetadataOrNull(String key, boolean replacement) {
    try {
      return replacement ? nativeBoundary.recycleReplacementMetadata(key)
          : nativeBoundary.recycleMetadata(key);
    } catch (NativeBoundaryException exception) {
      if (isNativeMissing(exception)) return null;
      throw exception;
    }
  }

  private NativeFileMetadata visibleNativeMetadataOrNull(String path) {
    try {
      return nativeBoundary.metadata(path);
    } catch (NativeBoundaryException exception) {
      if (isNativeMissing(exception)) return null;
      throw exception;
    }
  }

  private String visibleFingerprint(
      SharedFolderPathResolver resolver, String relative, Path target) {
    return SharedFolderObservedItemTokens.token(relative, resolver.readHandle(target).attributes());
  }

  private void requirePortableFingerprint(
      String expected, String directory, String key, String relative) throws IOException {
    String current = SharedFolderObservedItemTokens.token(
        relative, privateBoundary.metadata(directory, key).attributes());
    if (!SharedFolderObservedItemTokens.matches(expected, current)) throw conflict();
  }

  private void requirePortableIdentity(
      String expected, String directory, String key) throws IOException {
    var metadata = privateBoundary.metadata(directory, key);
    String current = portableIdentity(metadata.stableIdentity(), metadata.attributes());
    if (!expected.equals(current)) throw conflict();
  }

  private String portableIdentity(Path path, BasicFileAttributes attributes) throws IOException {
    return portableIdentity(PortableSharedFolderPrivateBoundary.stableIdentity(path), attributes);
  }

  private String portableIdentity(Object stableIdentity, BasicFileAttributes attributes) {
    return stableIdentity + ":" + attributes.isDirectory() + ":" + attributes.isRegularFile();
  }

  private SharedFolderRecycleItem preparing(
      String id, String path, Account account, Instant now, String key, long size,
      boolean directory, String fingerprint, String sourceIdentity) {
    if (account == null || account.getId() == null || account.getId().isBlank()) {
      throw new org.springframework.security.access.AccessDeniedException(
          "Shared-folder access denied");
    }
    return new SharedFolderRecycleItem(
        id, path, account.getId(), now, now.plus(properties.recycleRetention()), key,
        size, directory, fingerprint, SharedFolderRecycleState.PREPARING, null, null,
        sourceIdentity);
  }

  private SharedFolderRecycleItem current(String id) {
    return repository.findById(requiredId(id))
        .filter(item -> item.state() == SharedFolderRecycleState.RECYCLED)
        .orElseThrow(this::notFound);
  }

  private String requiredId(String id) {
    if (id == null || id.isBlank() || id.length() > 128 || id.indexOf('/') >= 0
        || id.indexOf('\\') >= 0 || id.indexOf(':') >= 0) {
      throw badRequest();
    }
    return id;
  }

  private SharedDirectoryEntry describe(String relative, Path target) throws IOException {
    BasicFileAttributes attributes = Files.readAttributes(
        target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    return new SharedDirectoryEntry(
        target.getFileName().toString(), relative,
        attributes.isDirectory() ? SharedDirectoryEntryType.DIRECTORY : SharedDirectoryEntryType.FILE,
        attributes.isRegularFile() ? attributes.size() : 0,
        attributes.lastModifiedTime().toInstant(), SharedFolderPreviewKind.NONE,
        SharedFolderObservedItemTokens.token(relative, attributes));
  }

  private SharedDirectoryEntry describeNative(String relative, NativeFileMetadata metadata) {
    return new SharedDirectoryEntry(
        leafName(relative), relative,
        metadata.directory() ? SharedDirectoryEntryType.DIRECTORY : SharedDirectoryEntryType.FILE,
        metadata.regularFile() ? metadata.size() : 0, metadata.modifiedAt(),
        SharedFolderPreviewKind.NONE, nativeVisibleToken(relative, metadata));
  }

  private String nativeVisibleToken(String relative, NativeFileMetadata metadata) {
    return SharedFolderObservedItemTokens.token(
        relative, nativeIdentity(metadata), metadata.directory(), metadata.size(),
        metadata.modifiedAt());
  }

  private String nativeFingerprint(NativeFileMetadata metadata) {
    return nativeIdentity(metadata) + ":" + metadata.directory() + ":" + metadata.regularFile()
        + ":" + metadata.size() + ":" + metadata.modifiedAt();
  }

  private String nativeStableIdentity(NativeFileMetadata metadata) {
    return nativeIdentity(metadata) + ":" + metadata.directory() + ":" + metadata.regularFile();
  }

  private String nativeIdentity(NativeFileMetadata metadata) {
    return metadata.identity().volumeSerial() + ":"
        + Base64.getUrlEncoder().withoutPadding().encodeToString(metadata.identity().fileId());
  }

  private void requireNativeFingerprint(
      SharedFolderRecycleItem item, NativeFileMetadata metadata) {
    if (!item.sourceFingerprint().equals(nativeFingerprint(metadata))) throw conflict();
  }

  private void requireNativeIdentity(
      SharedFolderRecycleItem item, NativeFileMetadata metadata) {
    if (!item.sourceIdentity().equals(nativeStableIdentity(metadata))) throw conflict();
  }

  private void requireNativeReplacementFingerprint(
      SharedFolderRecycleItem item, NativeFileMetadata metadata) {
    if (item.replacementFingerprint() == null
        || !item.replacementFingerprint().equals(nativeFingerprint(metadata))) throw conflict();
  }

  private String parentPath(String relative) {
    int separator = relative.lastIndexOf('/');
    return separator < 0 ? "" : relative.substring(0, separator);
  }

  private String leafName(String relative) {
    int separator = relative.lastIndexOf('/');
    return relative.substring(separator + 1);
  }

  private void requireEnabled() {
    if (!properties.enabled()) throw unavailable();
  }

  private void recordFor(Account account, String action, String path, long size) {
    if (audit != null) audit.recordFor(account, action, path, size, "accepted", null);
  }

  private ResponseStatusException badRequest() {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid recycle request");
  }

  private ResponseStatusException conflict() {
    return new ResponseStatusException(HttpStatus.CONFLICT, "Recycle destination conflicts");
  }

  private ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Recycle item was not found");
  }

  private ResponseStatusException unavailable() {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
        "Shared-folder recycle is unavailable");
  }

  private ResponseStatusException nativeFailure(NativeBoundaryException exception) {
    if (exception.kind() == NativeBoundaryException.Kind.CONFLICT) return conflict();
    if (exception.kind() == NativeBoundaryException.Kind.UNAVAILABLE) return unavailable();
    if (isNativeMissing(exception)) return notFound();
    int status = exception.ntStatus();
    if (status == 0xC0000035 || status == 0xC0000043 || status == 32
        || status == 80 || status == 183 || status == 0xC0000101) return conflict();
    return unavailable();
  }

  private boolean isNativeMissing(NativeBoundaryException exception) {
    int status = exception.ntStatus();
    return status == 0xC0000034 || status == 0xC000003A || status == 2 || status == 3;
  }
}
