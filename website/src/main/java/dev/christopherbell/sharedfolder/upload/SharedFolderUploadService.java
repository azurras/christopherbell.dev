package dev.christopherbell.sharedfolder.upload;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.configuration.filter.RequestPayloadTooLargeException;
import dev.christopherbell.sharedfolder.fs.SharedFolderPathResolver;
import dev.christopherbell.sharedfolder.fs.PortableSharedFolderPrivateBoundary;
import dev.christopherbell.sharedfolder.fs.PortableSharedFolderPrivateBoundary.BoundaryUnavailableException;
import dev.christopherbell.sharedfolder.fs.PortableSharedFolderPrivateBoundary.CheckedPathOperation;
import dev.christopherbell.sharedfolder.fs.UnsafeSharedPathException;
import dev.christopherbell.sharedfolder.fs.UnsafeSharedPathException;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderMutationBoundary;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeBoundaryException;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeFileMetadata;
import dev.christopherbell.sharedfolder.service.SharedFolderObservedItemTokens;
import dev.christopherbell.sharedfolder.security.SharedFolderAccessService;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

/** Streams bounded, owned upload chunks to private disk staging and atomically finalizes them. */
@Service
public class SharedFolderUploadService {
  private static final String STAGING_DIRECTORY = "shared-folder-upload-staging";
  private static final String QUARANTINE_DIRECTORY = "shared-folder-upload-quarantine";
  private static final Duration SESSION_TTL = Duration.ofHours(24);
  private static final Duration APPEND_LEASE_TTL = Duration.ofMinutes(2);
  private static final Duration FINALIZATION_LEASE_TTL = Duration.ofMinutes(2);
  private static final int COPY_BUFFER_SIZE = 16 * 1024;
  private static final long LEASE_RENEWAL_BYTES = 1024L * 1024L;

  private final SharedFolderAccessService access;
  private final SharedFolderUploadSessionRepository sessions;
  private final SharedFolderProperties properties;
  private final WindowsSharedFolderMutationBoundary nativeBoundary;
  private final PortableSharedFolderPrivateBoundary privateBoundary;
  private final String serviceInstanceId = UUID.randomUUID().toString();

  /** Creates the upload coordinator. */
  public SharedFolderUploadService(
      SharedFolderAccessService access,
      SharedFolderUploadSessionRepository sessions,
      SharedFolderProperties properties) {
    this(access, sessions, properties, WindowsSharedFolderMutationBoundary.inactive());
  }

  /** Creates the production upload service and preserves native held-root fail-closed semantics. */
  @Autowired
  public SharedFolderUploadService(
      SharedFolderAccessService access,
      SharedFolderUploadSessionRepository sessions,
      SharedFolderProperties properties,
      WindowsSharedFolderMutationBoundary nativeBoundary) {
    this.access = access;
    this.sessions = sessions;
    this.properties = properties;
    this.nativeBoundary = nativeBoundary;
    this.privateBoundary = new PortableSharedFolderPrivateBoundary(properties.systemRoot());
  }

  /** Starts an owned upload after validating a safe relative destination and private staging root. */
  public SharedFolderUploadStatus create(SharedFolderUploadCreateRequest request) {
    Account account = access.requireWrite();
    validateCreateRequest(request);
    if (!properties.enabled()) {
      throw unavailable();
    }
    if (request.expectedBytes() > properties.maxUpload().toBytes()) {
      throw tooLarge();
    }
    if (nativeBoundary.nativeMode()) {
      return createNative(request, account);
    }
    try {
      SharedFolderPathResolver resolver = portableResolver();
      Path requestedTarget = resolver.newChild(request.parentPath(), request.name());
      String canonicalName = request.name();
      if (request.targetObservedToken() != null
          && Files.exists(requestedTarget, LinkOption.NOFOLLOW_LINKS)) {
        String requestedRelative = request.parentPath().isEmpty() ? request.name()
            : request.parentPath() + "/" + request.name();
        canonicalName = resolver.existing(requestedRelative)
            .toRealPath(LinkOption.NOFOLLOW_LINKS)
            .getFileName().toString();
      }
      operateOnStagingDirectory(staging -> {
        requireReserve(staging, request.expectedBytes());
        return null;
      });
      Instant now = Instant.now();
      SharedFolderUploadSession session = new SharedFolderUploadSession();
      session.setId(UUID.randomUUID().toString());
      session.setOwnerId(requiredAccountId(account));
      session.setParentPath(request.parentPath());
      session.setName(canonicalName);
      session.setExpectedBytes(request.expectedBytes());
      session.setExpectedSha256(normalizeDigest(request.sha256()));
      session.setTargetObservedToken(request.targetObservedToken());
      session.setNextOffset(0);
      session.setStagingKey(UUID.randomUUID().toString());
      session.setState(SharedFolderUploadState.ACTIVE);
      session.setCreatedAt(now);
      session.setUpdatedAt(now);
      session.setExpiresAt(now.plus(SESSION_TTL));
      return status(sessions.save(session));
    } catch (UnsafeSharedPathException exception) {
      throw notFound();
    } catch (IOException | SecurityException exception) {
      throw conflict();
    }
  }

  /** Streams one ordered chunk with a SHA-256 check; identical ordered retries are idempotent. */
  public SharedFolderUploadStatus append(
      String id, long offset, InputStream body, String suppliedDigest) {
    validateAppendRequest(id, offset, body, suppliedDigest);
    Account account = access.requireWrite();
    String expectedDigest = normalizeDigest(suppliedDigest);
    requireEnabled();
    SharedFolderUploadSession session = ownedForAppend(id, account, offset, expectedDigest);
    if (nativeBoundary.nativeMode()) {
      return appendNative(session, account, offset, body, expectedDigest);
    }
    try {
      long neededBytes = session.getExpectedBytes() - session.getNextOffset();
      operateOnStagingDirectory(staging -> {
        requireReserve(staging, neededBytes);
        return null;
      });
      if (offset < session.getNextOffset()) {
        return verifyRetry(session, offset, body, expectedDigest);
      }
      if (offset != session.getNextOffset()) {
        throw conflict();
      }
      Chunk chunk = stageChunk(session, offset, body, expectedDigest);
      boolean appendStarted = false;
      boolean finalProgressSaveAttempted = false;
      String appendLeaseToken = null;
      try {
        try {
          session = acquireAppendLease(session, account, offset, chunk.digest(), chunk.length(), null);
        } catch (ResponseStatusException leaseConflict) {
          return status(awaitMatchingCommittedChunk(
              session.getId(), account, offset, chunk.digest(), chunk.length()));
        }
        appendLeaseToken = session.getAppendLeaseToken();
        renewAppendLease(session);
        reconcilePortableAppendLength(session, offset);
        appendStarted = true;
        appendStagedChunk(session, chunk.key());
        renewAppendLease(session);
        recordChunk(session, offset, chunk.digest(), chunk.length());
        clearAppendLease(session);
        session.setUpdatedAt(Instant.now());
        try {
          finalProgressSaveAttempted = true;
          return status(sessions.save(session));
        } catch (RuntimeException exception) {
          return status(reloadCommittedAppend(
              session.getId(), account, offset, chunk.digest(), chunk.length(), appendLeaseToken));
        }
      } catch (AppendLeaseLostException exception) {
        throw exception;
      } catch (RuntimeException | IOException exception) {
        if (appendStarted && !finalProgressSaveAttempted) {
          renewAppendLease(session);
          rollbackPortableAppend(session, offset);
          forgetChunk(session, offset);
          clearAppendLease(session);
          try {
            sessions.save(session);
          } catch (RuntimeException ignored) {
            // The expired durable lease will reconcile the physical length on another instance.
          }
        }
        if (exception instanceof ResponseStatusException responseStatusException) {
          throw responseStatusException;
        }
        throw conflict();
      } finally {
        deleteStagingKeyIfExists(chunk.key());
      }
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (RequestPayloadTooLargeException exception) {
      throw tooLarge();
    } catch (IOException | ArithmeticException | SecurityException exception) {
      throw conflict();
    }
  }

  /** Returns current owned upload progress and performs the same fresh write authorization check. */
  public SharedFolderUploadStatus status(String id) {
    Account account = access.requireWrite();
    requireEnabled();
    return status(reconcilePending(ownedActiveOrTerminal(id, account)));
  }

  /** Cancels an active owned upload and removes only its private random staging file. */
  public SharedFolderUploadStatus cancel(String id) {
    Account account = access.requireWrite();
    requireEnabled();
    SharedFolderUploadSession session = ownedActiveOrTerminal(id, account);
    if (session.getState() == SharedFolderUploadState.CANCELLED) {
      return status(session);
    }
    if (session.getState() != SharedFolderUploadState.ACTIVE
        && session.getState() != SharedFolderUploadState.CANCEL_PENDING) {
      throw conflict();
    }
    if (session.getState() == SharedFolderUploadState.ACTIVE) {
      session.setState(SharedFolderUploadState.CANCEL_PENDING);
      session.setUpdatedAt(Instant.now());
      try {
        session = sessions.save(session);
      } catch (RuntimeException exception) {
        throw conflict();
      }
    }
    if (nativeBoundary.nativeMode()) {
      try {
        nativeBoundary.deleteStagingIfExists(session.getStagingKey());
      } catch (NativeBoundaryException exception) {
        throw nativeFailure(exception, false);
      } catch (SecurityException exception) {
        throw unavailable();
      }
      session.setState(SharedFolderUploadState.CANCELLED);
      session.setUpdatedAt(Instant.now());
      try {
        return status(sessions.save(session));
      } catch (RuntimeException exception) {
        throw conflict();
      }
    }
    try {
      deleteStagingIfExists(session);
    } catch (IOException exception) {
      throw conflict();
    }
    session.setState(SharedFolderUploadState.CANCELLED);
    session.setUpdatedAt(Instant.now());
    try {
      return status(sessions.save(session));
    } catch (RuntimeException exception) {
      throw conflict();
    }
  }

  /** Rechecks ownership, target conflict, disk and same-volume identity before an atomic finalize. */
  public SharedFolderUploadStatus complete(String id, boolean replace) {
    Account account = access.requireWrite();
    requireEnabled();
    SharedFolderUploadSession session = ownedActiveOrTerminal(id, account);
    Boolean pendingReplace = session.getFinalizingReplace();
    session = reconcilePending(session);
    if (session.getState() == SharedFolderUploadState.FINALIZING
        && finalizationLeaseIsLive(session)) {
      throw conflict();
    }
    if (pendingReplace != null && session.getState() == SharedFolderUploadState.ACTIVE) {
      replace = pendingReplace;
    }
    if (session.getState() == SharedFolderUploadState.COMPLETED) {
      return status(session);
    }
    if (session.getState() != SharedFolderUploadState.ACTIVE
        && session.getState() != SharedFolderUploadState.FINALIZING) {
      throw conflict();
    }
    if (session.getNextOffset() != session.getExpectedBytes()) {
      throw conflict();
    }
    if (nativeBoundary.nativeMode()) {
      return completeNative(session, replace);
    }
    try {
      verifyCompleteDigest(session);
      SharedFolderPathResolver resolver = portableResolver();
      Path target = resolver.newChild(session.getParentPath(), session.getName());
      Path existingTarget = null;
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        if (!replace || session.getTargetObservedToken() == null) {
          throw conflict();
        }
        existingTarget = resolver.existing(relativePath(session));
        requireTargetToken(resolver, session, existingTarget);
      }
      resolver.recheckForMutation(target.getParent());
      if (existingTarget != null) {
        requireTargetToken(resolver, session, existingTarget);
        requirePortableReplacementTarget(existingTarget);
      }
      requireStagingSameFileStore(session, target.getParent());
      if (session.getState() == SharedFolderUploadState.ACTIVE) {
        BasicFileAttributes stagedAttributes = stagingAttributes(session);
        session = beginFinalizing(
            session,
            portableIdentity(stagedAttributes),
            replace,
            existingTarget == null ? null : portableReplacementIdentity(existingTarget));
        afterPhysicalFinalizationTransition(SharedFolderUploadFinalizationState.PREPARED);
      }
      if (replace) {
        session = finalizePortableReplacement(session, target, existingTarget);
      } else {
        renewFinalizationLease(session);
        moveStagingToVisible(session, target);
      }
      String completedFinalizationLeaseToken = session.getFinalizationLeaseToken();
      session.setState(SharedFolderUploadState.COMPLETED);
      clearFinalization(session);
      session.setUpdatedAt(Instant.now());
      try {
        return status(sessions.save(session));
      } catch (RuntimeException exception) {
        return status(reloadCompletedFinalization(
            session.getId(), completedFinalizationLeaseToken));
      }
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (UnsafeSharedPathException exception) {
      throw notFound();
    } catch (IOException | SecurityException exception) {
      throw conflict();
    }
  }

  private SharedFolderUploadStatus verifyRetry(
      SharedFolderUploadSession session, long offset, InputStream body, String suppliedDigest) throws IOException {
    String offsetKey = Long.toString(offset);
    String storedDigest = session.getChunkDigests().get(offsetKey);
    Long storedLength = session.getChunkLengths().get(offsetKey);
    if (storedDigest == null || storedLength == null || !constantTimeEquals(storedDigest, suppliedDigest)) {
      throw conflict();
    }
    DigestAndLength retry = digestBounded(body, properties.uploadChunk().toBytes(), storedLength);
    if (retry.length() != storedLength || !constantTimeEquals(retry.digest(), storedDigest)) {
      throw conflict();
    }
    return status(session);
  }

  private SharedFolderUploadStatus createNative(
      SharedFolderUploadCreateRequest request, Account account) {
    String stagingKey = UUID.randomUUID().toString();
    boolean staged = false;
    try {
      SharedFolderPathResolver.validateSingleWindowsName(request.name());
      nativeBoundary.directory(request.parentPath());
      String canonicalName = request.targetObservedToken() == null ? request.name()
          : nativeBoundary.canonicalChildName(request.parentPath(), request.name());
      requireNativeReserve(request.expectedBytes());
      nativeBoundary.createStaging(stagingKey).close();
      staged = true;
      Instant now = Instant.now();
      SharedFolderUploadSession session = new SharedFolderUploadSession();
      session.setId(UUID.randomUUID().toString());
      session.setOwnerId(requiredAccountId(account));
      session.setParentPath(request.parentPath());
      session.setName(canonicalName);
      session.setExpectedBytes(request.expectedBytes());
      session.setExpectedSha256(normalizeDigest(request.sha256()));
      session.setTargetObservedToken(request.targetObservedToken());
      session.setNextOffset(0);
      session.setStagingKey(stagingKey);
      session.setState(SharedFolderUploadState.ACTIVE);
      session.setCreatedAt(now);
      session.setUpdatedAt(now);
      session.setExpiresAt(now.plus(SESSION_TTL));
      return status(sessions.save(session));
    } catch (RuntimeException exception) {
      if (staged) {
        try {
          nativeBoundary.deleteStaging(stagingKey);
        } catch (RuntimeException ignored) {
          // The safe response remains a generic conflict; cleanup can be retried by expiry maintenance.
        }
      }
      if (exception instanceof ResponseStatusException responseStatusException) {
        throw responseStatusException;
      }
      if (exception instanceof NativeBoundaryException nativeBoundaryException) {
        throw nativeFailure(nativeBoundaryException, false);
      }
      throw conflict();
    }
  }

  private SharedFolderUploadStatus appendNative(
      SharedFolderUploadSession session, Account account, long offset,
      InputStream body, String suppliedDigest) {
    try {
      requireNativeReserve(session.getExpectedBytes() - session.getNextOffset());
      if (offset < session.getNextOffset()) {
        return verifyRetry(session, offset, body, suppliedDigest);
      }
      if (offset != session.getNextOffset()) {
        throw conflict();
      }
      String chunkKey = UUID.randomUUID().toString();
      DigestAndLength chunk;
      boolean appendStarted = false;
      String appendLeaseToken = null;
      try {
        try (var stagedChunk = nativeBoundary.createStaging(chunkKey)) {
          chunk = writeNativeBounded(
              body, stagedChunk, properties.uploadChunk().toBytes(),
              session.getExpectedBytes() - session.getNextOffset());
          if (chunk.length() == 0 || !constantTimeEquals(chunk.digest(), suppliedDigest)) {
            throw conflict();
          }
          stagedChunk.flush();
        }
        try {
          session = acquireAppendLease(
              session, account, offset, chunk.digest(), chunk.length(), chunkKey);
        } catch (ResponseStatusException leaseConflict) {
          return status(awaitMatchingCommittedChunk(
              session.getId(), account, offset, chunk.digest(), chunk.length()));
        }
        appendLeaseToken = session.getAppendLeaseToken();
        renewAppendLease(session);
        appendStarted = true;
        appendNativeChunk(session, chunkKey, offset);
      } catch (AppendLeaseLostException exception) {
        throw exception;
      } catch (RuntimeException exception) {
        if (appendStarted) {
          renewAppendLease(session);
          rollbackNativeAppend(session.getStagingKey(), offset);
          forgetChunk(session, offset);
        }
        throw exception;
      } finally {
        try {
          nativeBoundary.deleteStaging(chunkKey);
        } catch (RuntimeException ignored) {
          // A random private orphan is safe and can be removed by expiry maintenance.
        }
      }
      renewAppendLease(session);
      recordChunk(session, offset, chunk.digest(), chunk.length());
      clearAppendLease(session);
      session.setUpdatedAt(Instant.now());
      try {
        return status(sessions.save(session));
      } catch (RuntimeException exception) {
        return status(reloadCommittedAppend(
            session.getId(), account, offset, chunk.digest(), chunk.length(), appendLeaseToken));
      }
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (RequestPayloadTooLargeException exception) {
      throw tooLarge();
    } catch (NativeBoundaryException exception) {
      throw nativeFailure(exception, false);
    } catch (IOException | ArithmeticException | SecurityException exception) {
      throw conflict();
    }
  }

  private DigestAndLength writeNativeBounded(
      InputStream body,
      WindowsSharedFolderMutationBoundary.NativeStagingFile target,
      long chunkLimit,
      long remaining) throws IOException {
    if (body == null || remaining < 0) {
      throw conflict();
    }
    try (InputStream input = body) {
      MessageDigest digest = sha256();
      long written = 0;
      byte[] buffer = new byte[COPY_BUFFER_SIZE];
      for (int count; (count = input.read(buffer)) != -1;) {
        if (count == 0) {
          continue;
        }
        if (written > chunkLimit - count || written > remaining - count) {
          throw tooLarge();
        }
        writeFully(target, buffer, count);
        digest.update(buffer, 0, count);
        written += count;
      }
      return new DigestAndLength(encodeDigest(digest.digest()), written);
    }
  }

  private void appendNativeChunk(
      SharedFolderUploadSession session, String chunkKey, long expectedOffset) {
    try (var target = nativeBoundary.staging(session.getStagingKey());
        var source = nativeBoundary.staging(chunkKey)) {
      long physicalSize = target.metadata().size();
      if (physicalSize < expectedOffset) {
        throw conflict();
      }
      if (physicalSize > expectedOffset) {
        target.truncate(expectedOffset);
        target.flush();
      }
      if (target.seek(expectedOffset) != expectedOffset) {
        throw conflict();
      }
      source.seek(0);
      byte[] buffer = new byte[COPY_BUFFER_SIZE];
      long sinceRenewal = 0;
      Instant lastRenewalAt = leaseNow();
      for (int count; (count = source.read(buffer, 0, buffer.length)) != -1;) {
        writeFully(target, buffer, count);
        sinceRenewal += count;
        Instant now = leaseNow();
        if (sinceRenewal >= LEASE_RENEWAL_BYTES
            || !now.isBefore(lastRenewalAt.plus(appendLeaseRenewalInterval()))) {
          renewAppendLease(session);
          sinceRenewal = 0;
          lastRenewalAt = now;
        }
      }
      renewAppendLease(session);
      target.flush();
    }
  }

  private void writeFully(
      WindowsSharedFolderMutationBoundary.NativeStagingFile target, byte[] buffer, int length) {
    int offset = 0;
    while (offset < length) {
      int count = target.write(buffer, offset, length - offset);
      if (count <= 0) {
        throw new NativeBoundaryException("native shared-folder write made no progress", 0);
      }
      offset += count;
    }
  }

  private void recordChunk(
      SharedFolderUploadSession session, long offset, String digest, long length) {
    session.getChunkDigests().put(Long.toString(offset), digest);
    session.getChunkLengths().put(Long.toString(offset), length);
    session.setNextOffset(Math.addExact(offset, length));
  }

  private SharedFolderUploadSession acquireAppendLease(
      SharedFolderUploadSession previous,
      Account account,
      long offset,
      String digest,
      long length,
      String chunkKey) {
    SharedFolderUploadSession current = ownedActive(previous.getId(), account);
    if (current.getNextOffset() != offset) {
      throw conflict();
    }
    current.setState(SharedFolderUploadState.APPENDING);
    current.setAppendLeaseToken(serviceInstanceId + ":" + UUID.randomUUID());
    Instant now = leaseNow();
    current.setAppendLeaseExpiresAt(now.plus(appendLeaseDuration()));
    current.setAppendOffset(offset);
    current.setAppendLength(length);
    current.setAppendDigest(digest);
    current.setAppendChunkKey(chunkKey);
    current.setUpdatedAt(now);
    try {
      return sessions.save(current);
    } catch (RuntimeException exception) {
      throw conflict();
    }
  }

  private void clearAppendLease(SharedFolderUploadSession session) {
    session.setState(SharedFolderUploadState.ACTIVE);
    session.setAppendLeaseToken(null);
    session.setAppendLeaseExpiresAt(null);
    session.setAppendOffset(null);
    session.setAppendLength(null);
    session.setAppendDigest(null);
    session.setAppendChunkKey(null);
  }

  private void renewAppendLease(SharedFolderUploadSession session) {
    Instant now = leaseNow();
    Instant expiresAt = now.plus(appendLeaseDuration());
    long renewed = sessions.renewAppendLease(
        session.getId(), session.getAppendLeaseToken(), session.getAppendOffset(), expiresAt, now);
    if (renewed != 1L) {
      throw new AppendLeaseLostException();
    }
    session.setAppendLeaseExpiresAt(expiresAt);
    session.setUpdatedAt(now);
  }

  /** Test seam for deterministic short append leases. */
  protected Duration appendLeaseDuration() { return APPEND_LEASE_TTL; }

  private Duration appendLeaseRenewalInterval() {
    Duration interval = appendLeaseDuration().dividedBy(3);
    return interval.isZero() ? Duration.ofMillis(1) : interval;
  }

  private void reconcilePortableAppendLength(
      SharedFolderUploadSession session, long committedOffset) throws IOException {
    operateOnStaging(session, stagingFile -> {
      if (Files.notExists(stagingFile, LinkOption.NOFOLLOW_LINKS)) {
        if (committedOffset == 0) {
          return null;
        }
        throw conflict();
      }
      long size = Files.size(stagingFile);
      if (size < committedOffset) {
        throw conflict();
      }
      if (size > committedOffset) {
        truncatePortableFile(stagingFile, committedOffset);
      }
      return null;
    });
  }

  private SharedFolderUploadSession reconcileExpiredAppendLease(
      SharedFolderUploadSession session) {
    Instant now = leaseNow();
    if (session.getState() != SharedFolderUploadState.APPENDING
        || session.getAppendLeaseExpiresAt() != null
            && session.getAppendLeaseExpiresAt().isAfter(now)) {
      return session;
    }
    beforeExpiredAppendLeaseClaim();
    String recoveryToken = serviceInstanceId + ":append-recovery:" + UUID.randomUUID();
    Instant recoveryExpiry = now.plus(appendLeaseDuration());
    long claimed = sessions.claimExpiredAppendLease(
        session.getId(), session.getAppendLeaseToken(), session.getAppendOffset(), now,
        recoveryToken, recoveryExpiry, now);
    if (claimed != 1L) {
      return sessions.findById(session.getId()).orElseThrow(this::conflict);
    }
    session.setAppendLeaseToken(recoveryToken);
    session.setAppendLeaseExpiresAt(recoveryExpiry);
    session.setUpdatedAt(now);
    try {
      if (nativeBoundary.nativeMode()) {
        renewAppendLease(session);
        beforeExpiredAppendPhysicalTransition();
        rollbackNativeAppend(session.getStagingKey(), session.getNextOffset());
        if (session.getAppendChunkKey() != null) {
          try {
            renewAppendLease(session);
            beforeExpiredAppendPhysicalTransition();
            nativeBoundary.deleteStaging(session.getAppendChunkKey());
          } catch (RuntimeException ignored) {
            // The random private chunk is harmless and remains eligible for bounded cleanup.
          }
        }
      } else {
        renewAppendLease(session);
        beforeExpiredAppendPhysicalTransition();
        reconcilePortableAppendLength(session, session.getNextOffset());
        if (session.getAppendChunkKey() != null) {
          renewAppendLease(session);
          beforeExpiredAppendPhysicalTransition();
          deleteStagingKeyIfExists(session.getAppendChunkKey());
        }
      }
      renewAppendLease(session);
      clearAppendLease(session);
      session.setUpdatedAt(leaseNow());
      return sessions.save(session);
    } catch (AppendLeaseLostException exception) {
      throw exception;
    } catch (IOException | RuntimeException exception) {
      throw conflict();
    }
  }

  /** Test seam immediately before the exact expired-lease compare-and-set. */
  protected void beforeExpiredAppendLeaseClaim() { }

  /** Test seam immediately before an expired-append recovery changes private bytes. */
  protected void beforeExpiredAppendPhysicalTransition() { }

  private void forgetChunk(SharedFolderUploadSession session, long offset) {
    session.getChunkDigests().remove(Long.toString(offset));
    session.getChunkLengths().remove(Long.toString(offset));
    session.setNextOffset(offset);
  }

  private void rollbackNativeAppend(String stagingKey, long offset) {
    try (var staging = nativeBoundary.staging(stagingKey)) {
      staging.truncate(offset);
      staging.flush();
    } catch (RuntimeException exception) {
      throw new IllegalStateException("Native shared-folder append rollback failed", exception);
    }
  }

  private void rollbackPortableAppend(SharedFolderUploadSession session, long offset)
      throws IOException {
    operateOnStaging(session, stagingFile -> {
      truncatePortableFile(stagingFile, offset);
      return null;
    });
  }

  private void truncatePortableFile(Path stagingFile, long offset) {
    try (FileChannel channel = FileChannel.open(stagingFile, StandardOpenOption.WRITE)) {
      channel.truncate(offset);
      channel.force(true);
    } catch (IOException exception) {
      throw new IllegalStateException("Shared-folder append rollback failed", exception);
    }
  }

  private SharedFolderUploadStatus completeNative(
      SharedFolderUploadSession session, boolean replace) {
    try {
      verifyNativeCompleteDigest(session);
      nativeBoundary.directory(session.getParentPath());
      NativeFileMetadata target = null;
      if (replace) {
        if (session.getTargetObservedToken() == null) {
          throw conflict();
        }
        target = nativeBoundary.metadata(relativePath(session));
        requireNativeTargetToken(session, target);
      }
      requireNativeReserve(0);
      if (session.getState() == SharedFolderUploadState.ACTIVE) {
        try (var staged = nativeBoundary.staging(session.getStagingKey())) {
          session = beginFinalizing(
              session, nativeIdentity(staged.metadata()), replace,
              target == null ? null : nativeIdentity(target));
          afterPhysicalFinalizationTransition(SharedFolderUploadFinalizationState.PREPARED);
        }
      }
      if (replace) {
        session = finalizeNativeReplacement(session, target);
      } else {
        renewFinalizationLease(session);
        nativeBoundary.finalizeStaging(
            session.getStagingKey(), session.getParentPath(), session.getName(), false, null);
      }
      String completedFinalizationLeaseToken = session.getFinalizationLeaseToken();
      session.setState(SharedFolderUploadState.COMPLETED);
      clearFinalization(session);
      session.setUpdatedAt(Instant.now());
      try {
        return status(sessions.save(session));
      } catch (RuntimeException exception) {
        return status(reloadCompletedFinalization(
            session.getId(), completedFinalizationLeaseToken));
      }
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (NativeBoundaryException exception) {
      throw nativeFailure(exception, false);
    } catch (IOException exception) {
      throw conflict();
    } catch (SecurityException exception) {
      throw unavailable();
    }
  }

  private void verifyNativeCompleteDigest(SharedFolderUploadSession session) throws IOException {
    try (var staged = nativeBoundary.staging(session.getStagingKey())) {
      if (staged.metadata().size() != session.getExpectedBytes()) {
        throw conflict();
      }
      if (session.getExpectedSha256() == null) {
        return;
      }
      staged.seek(0);
      MessageDigest digest = sha256();
      long read = 0;
      byte[] buffer = new byte[COPY_BUFFER_SIZE];
      for (int count; (count = staged.read(buffer, 0, buffer.length)) != -1;) {
        if (read > session.getExpectedBytes() - count) {
          throw conflict();
        }
        digest.update(buffer, 0, count);
        read += count;
      }
      if (read != session.getExpectedBytes()
          || !constantTimeEquals(session.getExpectedSha256(), encodeDigest(digest.digest()))) {
        throw conflict();
      }
    }
  }

  private void requireNativeTargetToken(
      SharedFolderUploadSession session, NativeFileMetadata metadata) {
    String identity = metadata.identity().volumeSerial() + ":"
        + Base64.getUrlEncoder().withoutPadding().encodeToString(metadata.identity().fileId());
    String current = SharedFolderObservedItemTokens.token(
        relativePath(session), identity, metadata.directory(), metadata.size(), metadata.modifiedAt());
    if (!constantTimeEquals(session.getTargetObservedToken(), current)) {
      throw conflict();
    }
  }

  private SharedFolderUploadSession beginFinalizing(
      SharedFolderUploadSession session, String identity, boolean replace, String targetIdentity) {
    session.setFinalizingIdentity(identity);
    session.setFinalizingReplace(replace);
    session.setFinalizingTargetIdentity(targetIdentity);
    session.setFinalizingQuarantineKey(replace ? UUID.randomUUID().toString() : null);
    session.setFinalizationState(SharedFolderUploadFinalizationState.PREPARED);
    session.setFinalizationLeaseToken(serviceInstanceId + ":finalize:" + UUID.randomUUID());
    Instant now = leaseNow();
    session.setFinalizationLeaseExpiresAt(now.plus(finalizationLeaseDuration()));
    session.setState(SharedFolderUploadState.FINALIZING);
    session.setUpdatedAt(now);
    try {
      return sessions.save(session);
    } catch (RuntimeException exception) {
      session.setState(SharedFolderUploadState.ACTIVE);
      session.setFinalizingIdentity(null);
      session.setFinalizingReplace(null);
      session.setFinalizingTargetIdentity(null);
      session.setFinalizingQuarantineKey(null);
      session.setFinalizationState(null);
      session.setFinalizationLeaseToken(null);
      session.setFinalizationLeaseExpiresAt(null);
      throw conflict();
    }
  }

  private SharedFolderUploadSession finalizePortableReplacement(
      SharedFolderUploadSession session, Path target, Path observedTarget)
      throws IOException {
    requireStagingAndQuarantineSameFileStore(session);
    if (observedTarget == null
        || !portableReplacementIdentityMatches(
            observedTarget, session.getFinalizingTargetIdentity(), session)) {
      transition(session, SharedFolderUploadState.ACTIVE);
      throw conflict();
    }
    renewFinalizationLease(session);
    moveVisibleToQuarantine(session, observedTarget);
    boolean stagingMoved = false;
    try {
      if (!quarantineIdentityMatches(session)) {
        throw conflict();
      }
      afterPhysicalFinalizationTransition(SharedFolderUploadFinalizationState.TARGET_QUARANTINED);
      if (!quarantineIdentityMatches(session)) {
        throw conflict();
      }
      session = saveFinalizationPhase(
          session, SharedFolderUploadFinalizationState.TARGET_QUARANTINED);
      renewFinalizationLease(session);
      moveStagingToVisible(session, target);
      stagingMoved = true;
      afterPhysicalFinalizationTransition(SharedFolderUploadFinalizationState.SOURCE_MOVED);
      session = saveFinalizationPhase(session, SharedFolderUploadFinalizationState.SOURCE_MOVED);
      renewFinalizationLease(session);
      deleteQuarantine(session.getFinalizingQuarantineKey());
      return session;
    } catch (FinalizationLeaseLostException failure) {
      throw failure;
    } catch (IOException | RuntimeException failure) {
      if (!stagingMoved && Files.notExists(target, LinkOption.NOFOLLOW_LINKS)
          && quarantineExists(session.getFinalizingQuarantineKey())) {
        try {
          renewFinalizationLease(session);
          moveQuarantineToVisible(session.getFinalizingQuarantineKey(), target);
          transition(session, SharedFolderUploadState.ACTIVE);
        } catch (IOException | RuntimeException restoreFailure) {
          failure.addSuppressed(restoreFailure);
          markRestorePending(session);
        }
      } else {
        markRestorePending(session);
      }
      throw failure;
    }
  }

  private SharedFolderUploadSession finalizeNativeReplacement(
      SharedFolderUploadSession session, NativeFileMetadata expectedTarget) {
    renewFinalizationLease(session);
    NativeFileMetadata quarantined;
    try {
      quarantined = nativeBoundary.quarantineVisible(
          relativePath(session), session.getFinalizingQuarantineKey(), expectedTarget);
    } catch (NativeBoundaryException exception) {
      if (!isNativeMissing(exception)) {
        throw exception;
      }
      transition(session, SharedFolderUploadState.ACTIVE);
      throw conflict();
    }
    if (!Objects.equals(session.getFinalizingTargetIdentity(), nativeIdentity(quarantined))) {
      throw conflict();
    }
    afterPhysicalFinalizationTransition(SharedFolderUploadFinalizationState.TARGET_QUARANTINED);
    session = saveFinalizationPhase(session, SharedFolderUploadFinalizationState.TARGET_QUARANTINED);
    renewFinalizationLease(session);
    nativeBoundary.finalizeStaging(
        session.getStagingKey(), session.getParentPath(), session.getName(), false, null);
    afterPhysicalFinalizationTransition(SharedFolderUploadFinalizationState.SOURCE_MOVED);
    session = saveFinalizationPhase(session, SharedFolderUploadFinalizationState.SOURCE_MOVED);
    renewFinalizationLease(session);
    nativeBoundary.deleteQuarantine(session.getFinalizingQuarantineKey(), quarantined);
    return session;
  }

  private SharedFolderUploadSession saveFinalizationPhase(
      SharedFolderUploadSession session, SharedFolderUploadFinalizationState state) {
    renewFinalizationLease(session);
    session.setFinalizationState(state);
    Instant now = leaseNow();
    session.setUpdatedAt(now);
    session.setFinalizationLeaseExpiresAt(now.plus(finalizationLeaseDuration()));
    try {
      return sessions.save(session);
    } catch (RuntimeException exception) {
      throw conflict();
    }
  }

  /** Test seam for process death immediately after a physical finalization transition. */
  protected void afterPhysicalFinalizationTransition(SharedFolderUploadFinalizationState state) {
    // Process death is outside Java control; tests inject it at the same durable boundary.
  }

  private SharedFolderUploadSession reconcilePending(SharedFolderUploadSession session) {
    if (session.getState() == SharedFolderUploadState.CANCEL_PENDING) {
      if (!deletePendingStaging(session)) {
        return session;
      }
      return transition(session, SharedFolderUploadState.CANCELLED);
    }
    if (session.getState() != SharedFolderUploadState.FINALIZING) {
      return session;
    }
    if (finalizationLeaseIsLive(session)) {
      return session;
    }
    String finalizationId = session.getId();
    session = claimExpiredFinalization(session);
    if (session == null) {
      return sessions.findById(finalizationId).orElseThrow(this::notFound);
    }
    if (Boolean.TRUE.equals(session.getFinalizingReplace())) {
      return reconcileReplacementFinalization(session);
    }
    if (targetMatchesFinalizingIdentity(session)) {
      return transition(session, SharedFolderUploadState.COMPLETED);
    }
    if (stagingMatchesFinalizingIdentity(session)) {
      return transition(session, SharedFolderUploadState.ACTIVE);
    }
    return session;
  }

  private boolean finalizationLeaseIsLive(SharedFolderUploadSession session) {
    return session.getFinalizationLeaseToken() != null
        && session.getFinalizationLeaseExpiresAt() != null
        && session.getFinalizationLeaseExpiresAt().isAfter(leaseNow());
  }

  private SharedFolderUploadSession claimExpiredFinalization(SharedFolderUploadSession session) {
    Instant now = leaseNow();
    session.setFinalizationLeaseToken(serviceInstanceId + ":recovery:" + UUID.randomUUID());
    session.setFinalizationLeaseExpiresAt(now.plus(finalizationLeaseDuration()));
    session.setUpdatedAt(now);
    try {
      return sessions.save(session);
    } catch (RuntimeException optimisticConflict) {
      return null;
    }
  }


  private SharedFolderUploadSession reconcileReplacementFinalization(
      SharedFolderUploadSession session) {
    try {
      if (nativeBoundary.nativeMode()) {
        return reconcileNativeReplacementFinalization(session);
      }
      Path target = properties.root().toAbsolutePath().normalize().resolve(relativePath(session));
      renewFinalizationLease(session);
      boolean sourceAtTarget = portableIdentityMatches(target, session.getFinalizingIdentity());
      renewFinalizationLease(session);
      boolean sourceAtStaging = stagingIdentityMatches(session);
      boolean originalAtTarget = portableReplacementIdentityMatches(
          target, session.getFinalizingTargetIdentity(), session);
      boolean originalAtQuarantine = quarantineIdentityMatches(session);
      if (sourceAtTarget) {
        if (originalAtQuarantine) {
          renewFinalizationLease(session);
          deleteQuarantine(session.getFinalizingQuarantineKey());
        }
        return transition(session, SharedFolderUploadState.COMPLETED);
      }
      if (sourceAtStaging && originalAtTarget
          && !quarantineExists(session.getFinalizingQuarantineKey())) {
        return transition(session, SharedFolderUploadState.ACTIVE);
      }
      if (sourceAtStaging && Files.notExists(target) && originalAtQuarantine) {
        renewFinalizationLease(session);
        moveQuarantineToVisible(session.getFinalizingQuarantineKey(), target);
        return transition(session, SharedFolderUploadState.ACTIVE);
      }
      return markRestorePending(session);
    } catch (FinalizationLeaseLostException exception) {
      throw exception;
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (RuntimeException | IOException exception) {
      return markRestorePending(session);
    }
  }

  private SharedFolderUploadSession reconcileNativeReplacementFinalization(
      SharedFolderUploadSession session) {
    renewFinalizationLease(session);
    NativeFileMetadata target = nativeMetadataOrNull(relativePath(session));
    renewFinalizationLease(session);
    NativeFileMetadata staging = nativeStagingMetadataOrNull(session.getStagingKey());
    renewFinalizationLease(session);
    NativeFileMetadata quarantine = nativeQuarantineMetadataOrNull(
        session.getFinalizingQuarantineKey());
    boolean sourceAtTarget = nativeIdentityMatches(target, session.getFinalizingIdentity());
    boolean sourceAtStaging = nativeIdentityMatches(staging, session.getFinalizingIdentity());
    boolean originalAtTarget = nativeIdentityMatches(
        target, session.getFinalizingTargetIdentity());
    boolean originalAtQuarantine = nativeIdentityMatches(
        quarantine, session.getFinalizingTargetIdentity());
    try {
      if (sourceAtTarget) {
        if (originalAtQuarantine) {
          renewFinalizationLease(session);
          nativeBoundary.deleteQuarantine(session.getFinalizingQuarantineKey(), quarantine);
        }
        return transition(session, SharedFolderUploadState.COMPLETED);
      }
      if (sourceAtStaging && originalAtTarget && quarantine == null) {
        return transition(session, SharedFolderUploadState.ACTIVE);
      }
      if (sourceAtStaging && target == null && originalAtQuarantine) {
        renewFinalizationLease(session);
        nativeBoundary.restoreQuarantine(
            session.getFinalizingQuarantineKey(), session.getParentPath(), session.getName(),
            quarantine);
        return transition(session, SharedFolderUploadState.ACTIVE);
      }
      return markRestorePending(session);
    } catch (FinalizationLeaseLostException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      return markRestorePending(session);
    }
  }

  private SharedFolderUploadSession markRestorePending(SharedFolderUploadSession session) {
    renewFinalizationLease(session);
    session.setFinalizationState(SharedFolderUploadFinalizationState.RESTORE_PENDING);
    Instant now = leaseNow();
    session.setUpdatedAt(now);
    session.setFinalizationLeaseExpiresAt(now.plus(finalizationLeaseDuration()));
    try {
      return sessions.save(session);
    } catch (RuntimeException ignored) {
      return session;
    }
  }

  private void clearFinalization(SharedFolderUploadSession session) {
    session.setFinalizingIdentity(null);
    session.setFinalizingReplace(null);
    session.setFinalizingTargetIdentity(null);
    session.setFinalizingQuarantineKey(null);
    session.setFinalizationState(null);
    session.setFinalizationLeaseToken(null);
    session.setFinalizationLeaseExpiresAt(null);
  }

  private void renewFinalizationLease(SharedFolderUploadSession session) {
    Instant now = leaseNow();
    Instant expiresAt = now.plus(finalizationLeaseDuration());
    long renewed = sessions.renewFinalizationLease(
        session.getId(), session.getFinalizationLeaseToken(), session.getFinalizationState(),
        expiresAt, now);
    if (renewed != 1L) {
      throw new FinalizationLeaseLostException();
    }
    session.setFinalizationLeaseExpiresAt(expiresAt);
    session.setUpdatedAt(now);
  }

  /** Test seam for deterministic short finalization leases. */
  protected Instant leaseNow() { return Instant.now(); }

  /** Test seam for deterministic short finalization leases. */
  protected Duration finalizationLeaseDuration() { return FINALIZATION_LEASE_TTL; }

  private Duration finalizationLeaseRenewalInterval() {
    Duration interval = finalizationLeaseDuration().dividedBy(3);
    return interval.isZero() ? Duration.ofMillis(1) : interval;
  }

  private NativeFileMetadata nativeMetadataOrNull(String path) {
    try {
      return nativeBoundary.metadata(path);
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private NativeFileMetadata nativeStagingMetadataOrNull(String key) {
    try (var staging = nativeBoundary.staging(key)) {
      return staging.metadata();
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private NativeFileMetadata nativeQuarantineMetadataOrNull(String key) {
    try {
      return nativeBoundary.quarantineMetadata(key);
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private boolean nativeIdentityMatches(NativeFileMetadata metadata, String expected) {
    return metadata != null && Objects.equals(expected, nativeIdentity(metadata));
  }

  private boolean targetMatchesFinalizingIdentity(SharedFolderUploadSession session) {
    try {
      if (nativeBoundary.nativeMode()) {
        return Objects.equals(session.getFinalizingIdentity(),
            nativeIdentity(nativeBoundary.metadata(relativePath(session))));
      }
      SharedFolderPathResolver resolver = portableResolver();
      return Objects.equals(session.getFinalizingIdentity(), portableIdentity(Files.readAttributes(
          resolver.existing(relativePath(session)), BasicFileAttributes.class,
          LinkOption.NOFOLLOW_LINKS)));
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (RuntimeException | IOException exception) {
      return false;
    }
  }

  private boolean stagingMatchesFinalizingIdentity(SharedFolderUploadSession session) {
    try {
      if (nativeBoundary.nativeMode()) {
        try (var staging = nativeBoundary.staging(session.getStagingKey())) {
          return Objects.equals(session.getFinalizingIdentity(), nativeIdentity(staging.metadata()));
        }
      }
      return stagingIdentityMatches(session);
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (RuntimeException | IOException exception) {
      return false;
    }
  }

  private boolean stagingExists(SharedFolderUploadSession session) {
    try {
      if (nativeBoundary.nativeMode()) {
        try (var ignored = nativeBoundary.staging(session.getStagingKey())) {
          return true;
        }
      }
      return operateOnStaging(
          session, staging -> Files.isRegularFile(staging, LinkOption.NOFOLLOW_LINKS));
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (RuntimeException | IOException exception) {
      return false;
    }
  }

  private boolean deletePendingStaging(SharedFolderUploadSession session) {
    try {
      if (nativeBoundary.nativeMode()) {
        nativeBoundary.deleteStagingIfExists(session.getStagingKey());
      } else {
        deleteStagingIfExists(session);
      }
      return true;
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (RuntimeException | IOException exception) {
      return false;
    }
  }

  private boolean portableIdentityMatches(Path path, String expected) throws IOException {
    return expected != null && Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        && Objects.equals(expected, portableIdentity(Files.readAttributes(
            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)));
  }

  private boolean portableReplacementIdentityMatches(Path path, String expected) throws IOException {
    return expected != null && Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        && Objects.equals(expected, portableReplacementIdentity(path));
  }

  private boolean portableReplacementIdentityMatches(
      Path path, String expected, SharedFolderUploadSession session) throws IOException {
    return expected != null && Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        && Objects.equals(expected, portableReplacementIdentity(path, session));
  }

  private String portableReplacementIdentity(Path path) throws IOException {
    return portableReplacementIdentity(path, null);
  }

  private String portableReplacementIdentity(
      Path path, SharedFolderUploadSession session) throws IOException {
    Runnable heartbeat = session == null ? () -> { } : () -> renewFinalizationLease(session);
    heartbeat.run();
    BasicFileAttributes before = Files.readAttributes(
        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    String metadata = portableIdentity(before) + ":" + before.size() + ":"
        + before.lastModifiedTime().toMillis();
    if (before.isDirectory()) {
      try (var children = Files.list(path)) {
        if (children.findAny().isPresent()) throw conflict();
      }
      heartbeat.run();
      return metadata + ":empty-directory";
    }
    if (!before.isRegularFile()) throw conflict();
    MessageDigest digest = sha256();
    try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      byte[] buffer = new byte[COPY_BUFFER_SIZE];
      long sinceRenewal = 0;
      Instant lastRenewalAt = leaseNow();
      for (int count; (count = input.read(buffer)) != -1;) {
        digest.update(buffer, 0, count);
        sinceRenewal += count;
        Instant now = leaseNow();
        if (sinceRenewal >= LEASE_RENEWAL_BYTES
            || !now.isBefore(lastRenewalAt.plus(finalizationLeaseRenewalInterval()))) {
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
        || !Objects.equals(before.fileKey(), after.fileKey())) {
      throw conflict();
    }
    return metadata + ":sha256:" + encodeDigest(digest.digest());
  }

  private void requirePortableReplacementTarget(Path target) throws IOException {
    portableReplacementIdentity(target);
  }

  private SharedFolderUploadSession transition(
      SharedFolderUploadSession session, SharedFolderUploadState state) {
    SharedFolderUploadState previous = session.getState();
    String previousIdentity = session.getFinalizingIdentity();
    Boolean previousReplace = session.getFinalizingReplace();
    String previousTargetIdentity = session.getFinalizingTargetIdentity();
    String previousQuarantineKey = session.getFinalizingQuarantineKey();
    SharedFolderUploadFinalizationState previousFinalizationState = session.getFinalizationState();
    String previousLeaseToken = session.getFinalizationLeaseToken();
    Instant previousLeaseExpiry = session.getFinalizationLeaseExpiresAt();
    if (previous == SharedFolderUploadState.FINALIZING) {
      renewFinalizationLease(session);
    }
    session.setState(state);
    if (previous == SharedFolderUploadState.FINALIZING) {
      clearFinalization(session);
    }
    session.setUpdatedAt(Instant.now());
    try {
      return sessions.save(session);
    } catch (RuntimeException exception) {
      session.setState(previous);
      session.setFinalizingIdentity(previousIdentity);
      session.setFinalizingReplace(previousReplace);
      session.setFinalizingTargetIdentity(previousTargetIdentity);
      session.setFinalizingQuarantineKey(previousQuarantineKey);
      session.setFinalizationState(previousFinalizationState);
      session.setFinalizationLeaseToken(previousLeaseToken);
      session.setFinalizationLeaseExpiresAt(previousLeaseExpiry);
      return session;
    }
  }

  private String nativeIdentity(NativeFileMetadata metadata) {
    return metadata.identity().volumeSerial() + ":"
        + Base64.getUrlEncoder().withoutPadding().encodeToString(metadata.identity().fileId());
  }

  private String portableIdentity(BasicFileAttributes attributes) {
    Object key = attributes.fileKey();
    String identity = key == null ? attributes.creationTime().toString() : key.toString();
    return identity + ":" + attributes.isDirectory() + ":" + attributes.size()
        + ":" + attributes.lastModifiedTime();
  }

  private void requireNativeReserve(long neededBytes) {
    long available = nativeBoundary.usableSystemBytes();
    long reserve = properties.minimumFreeSpace().toBytes();
    if (neededBytes < 0 || available < reserve || available - reserve < neededBytes) {
      throw insufficientStorage();
    }
  }

  private Chunk stageChunk(
      SharedFolderUploadSession session, long offset, InputStream body, String suppliedDigest)
      throws IOException {
    String temporaryKey =
        session.getStagingKey() + ".chunk-" + offset + "-" + UUID.randomUUID();
    try {
      DigestAndLength digest = operateOnStagingKey(temporaryKey, temporary ->
          writeBounded(body, temporary, properties.uploadChunk().toBytes(),
              session.getExpectedBytes() - session.getNextOffset()));
      if (digest.length() == 0 || !constantTimeEquals(digest.digest(), suppliedDigest)) {
        throw conflict();
      }
      return new Chunk(temporaryKey, digest.digest(), digest.length());
    } catch (RuntimeException | IOException exception) {
      deleteStagingKeyIfExists(temporaryKey);
      throw exception;
    }
  }

  private DigestAndLength writeBounded(InputStream body, Path target, long chunkLimit, long remaining)
      throws IOException {
    if (body == null || remaining < 0) {
      throw conflict();
    }
    try (InputStream input = body;
        OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      return copyDigest(input, output, chunkLimit, remaining);
    }
  }

  private DigestAndLength digestBounded(InputStream body, long chunkLimit, long expectedLength)
      throws IOException {
    if (body == null) {
      throw conflict();
    }
    try (InputStream input = body) {
      return copyDigest(input, OutputStream.nullOutputStream(), chunkLimit, expectedLength);
    }
  }

  private DigestAndLength copyDigest(InputStream input, OutputStream output, long chunkLimit, long remaining)
      throws IOException {
    MessageDigest digest = sha256();
    long written = 0;
    byte[] buffer = new byte[COPY_BUFFER_SIZE];
    for (int count; (count = input.read(buffer)) != -1;) {
      if (count == 0) {
        continue;
      }
      if (written > chunkLimit - count || written > remaining - count) {
        throw tooLarge();
      }
      output.write(buffer, 0, count);
      digest.update(buffer, 0, count);
      written += count;
    }
    return new DigestAndLength(encodeDigest(digest.digest()), written);
  }

  private void appendStagedChunk(SharedFolderUploadSession session, String chunkKey)
      throws IOException {
    operateOnStaging(session, stagingFile -> operateOnStagingKey(chunkKey, chunk -> {
      appendStagedChunk(session, stagingFile, chunk);
      return null;
    }));
  }

  protected void appendStagedChunk(
      SharedFolderUploadSession session, Path stagingFile, Path chunk) throws IOException {
    try (InputStream input = Files.newInputStream(chunk);
        OutputStream output = Files.newOutputStream(stagingFile, StandardOpenOption.CREATE,
            StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
      byte[] buffer = new byte[COPY_BUFFER_SIZE];
      long sinceRenewal = 0;
      Instant lastRenewalAt = leaseNow();
      for (int count; (count = input.read(buffer)) != -1;) {
        output.write(buffer, 0, count);
        sinceRenewal += count;
        Instant now = leaseNow();
        if (sinceRenewal >= LEASE_RENEWAL_BYTES
            || !now.isBefore(lastRenewalAt.plus(appendLeaseRenewalInterval()))) {
          renewAppendLease(session);
          sinceRenewal = 0;
          lastRenewalAt = now;
        }
      }
    }
  }

  private void verifyCompleteDigest(SharedFolderUploadSession session) throws IOException {
    operateOnStaging(session, stagingFile -> {
      if (!Files.isRegularFile(stagingFile, LinkOption.NOFOLLOW_LINKS)
          || Files.size(stagingFile) != session.getExpectedBytes()) {
        throw conflict();
      }
      if (session.getExpectedSha256() == null) {
        return null;
      }
      DigestAndLength digest = digestBounded(
          Files.newInputStream(stagingFile), session.getExpectedBytes(),
          session.getExpectedBytes());
      if (!constantTimeEquals(session.getExpectedSha256(), digest.digest())) {
        throw conflict();
      }
      return null;
    });
  }

  private SharedFolderUploadSession ownedActive(String id, Account account) {
    SharedFolderUploadSession session = ownedActiveOrTerminal(id, account);
    if (session.getState() != SharedFolderUploadState.ACTIVE) {
      throw conflict();
    }
    return session;
  }

  private SharedFolderUploadSession ownedForAppend(
      String id, Account account, long offset, String digest) {
    SharedFolderUploadSession session = ownedActiveOrTerminal(id, account);
    if (session.getState() == SharedFolderUploadState.APPENDING
        && Objects.equals(session.getAppendOffset(), offset)
        && constantTimeEquals(session.getAppendDigest(), digest)) {
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
      while (session.getState() == SharedFolderUploadState.APPENDING
          && System.nanoTime() < deadline) {
        try {
          Thread.sleep(10);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw conflict();
        }
        session = ownedActiveOrTerminal(id, account);
      }
    }
    if (session.getState() != SharedFolderUploadState.ACTIVE) {
      throw conflict();
    }
    return session;
  }

  private SharedFolderUploadSession awaitMatchingCommittedChunk(
      String id, Account account, long offset, String digest, long length) {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
    SharedFolderUploadSession session;
    do {
      session = ownedActiveOrTerminal(id, account);
      if (session.getState() == SharedFolderUploadState.ACTIVE
          && session.getNextOffset() == offset + length
          && constantTimeEquals(session.getChunkDigests().get(Long.toString(offset)), digest)
          && Objects.equals(session.getChunkLengths().get(Long.toString(offset)), length)) {
        return session;
      }
      if (session.getState() != SharedFolderUploadState.APPENDING
          || !Objects.equals(session.getAppendOffset(), offset)
          || !constantTimeEquals(session.getAppendDigest(), digest)) {
        throw conflict();
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw conflict();
      }
    } while (System.nanoTime() < deadline);
    throw conflict();
  }

  private SharedFolderUploadSession reloadCommittedAppend(
      String id, Account account, long offset, String digest, long length, String appendLeaseToken) {
    SharedFolderUploadSession reloaded = sessions.findById(id).orElseThrow(this::conflict);
    if (!Objects.equals(reloaded.getOwnerId(), requiredAccountId(account))) {
      throw conflict();
    }
    if (reloaded.getState() == SharedFolderUploadState.ACTIVE
        && reloaded.getNextOffset() == Math.addExact(offset, length)
        && constantTimeEquals(reloaded.getChunkDigests().get(Long.toString(offset)), digest)
        && Objects.equals(reloaded.getChunkLengths().get(Long.toString(offset)), length)) {
      return reloaded;
    }
    if (reloaded.getState() == SharedFolderUploadState.APPENDING
        && reloaded.getNextOffset() == offset
        && Objects.equals(reloaded.getAppendLeaseToken(), appendLeaseToken)
        && Objects.equals(reloaded.getAppendOffset(), offset)
        && Objects.equals(reloaded.getAppendLength(), length)
        && constantTimeEquals(reloaded.getAppendDigest(), digest)) {
      reloaded.setAppendLeaseExpiresAt(Instant.now().minusSeconds(1));
      reloaded.setUpdatedAt(Instant.now());
      try {
        reconcileExpiredAppendLease(sessions.save(reloaded));
      } catch (RuntimeException exception) {
        throw conflict();
      }
    }
    throw conflict();
  }

  private SharedFolderUploadSession reloadCompletedFinalization(
      String id, String completedFinalizationLeaseToken) {
    SharedFolderUploadSession reloaded = sessions.findById(id).orElseThrow(this::conflict);
    if (reloaded.getState() == SharedFolderUploadState.COMPLETED) {
      return reloaded;
    }
    if (reloaded.getState() == SharedFolderUploadState.FINALIZING
        && Objects.equals(
            reloaded.getFinalizationLeaseToken(), completedFinalizationLeaseToken)) {
      reloaded.setFinalizationLeaseExpiresAt(Instant.now().minusSeconds(1));
      reloaded.setUpdatedAt(Instant.now());
      try {
        sessions.save(reloaded);
      } catch (RuntimeException ignored) {
        // A later status call will retry once the still-durable lease expires naturally.
      }
    }
    throw conflict();
  }

  private SharedFolderUploadSession ownedActiveOrTerminal(String id, Account account) {
    if (id == null || id.isBlank()) {
      throw invalid();
    }
    SharedFolderUploadSession session = sessions.findById(id).orElseThrow(this::notFound);
    if (!Objects.equals(session.getOwnerId(), requiredAccountId(account))) {
      throw notFound();
    }
    session = reconcileExpiredAppendLease(session);
    if (session.getState() == SharedFolderUploadState.ACTIVE
        && (session.getExpiresAt() == null || !session.getExpiresAt().isAfter(Instant.now()))) {
      session.setState(SharedFolderUploadState.EXPIRED);
      session.setUpdatedAt(Instant.now());
      session = sessions.save(session);
    }
    return session;
  }

  private void validateCreateRequest(SharedFolderUploadCreateRequest request) {
    if (request == null || request.expectedBytes() <= 0) {
      throw invalid();
    }
    try {
      SharedFolderPathResolver.safeRelativeSegments(request.parentPath(), true);
      SharedFolderPathResolver.validateSingleWindowsName(request.name());
    } catch (UnsafeSharedPathException exception) {
      throw invalid();
    }
    if (request.targetObservedToken() != null
        && (request.targetObservedToken().isBlank() || request.targetObservedToken().length() > 256)) {
      throw invalid();
    }
    if (request.sha256() != null) {
      try {
        normalizeDigest(request.sha256());
      } catch (ResponseStatusException exception) {
        throw invalid();
      }
    }
  }

  private void validateAppendRequest(
      String id, long offset, InputStream body, String suppliedDigest) {
    if (id == null || id.isBlank() || id.length() > 128 || offset < 0
        || body == null || suppliedDigest == null || suppliedDigest.isBlank()) {
      throw invalid();
    }
    try {
      normalizeDigest(suppliedDigest);
    } catch (ResponseStatusException exception) {
      throw invalid();
    }
  }

  private void requireEnabled() {
    if (!properties.enabled()) {
      throw unavailable();
    }
  }

  private SharedFolderPathResolver portableResolver() {
    Path root = properties.root().toAbsolutePath().normalize();
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
      throw unavailable();
    }
    try {
      return new SharedFolderPathResolver(root);
    } catch (SecurityException exception) {
      throw unavailable();
    }
  }

  private <T> T operateOnStagingDirectory(CheckedPathOperation<T> operation) throws IOException {
    return operateOnPrivateDirectory(STAGING_DIRECTORY, operation);
  }

  private <T> T operateOnStaging(
      SharedFolderUploadSession session, CheckedPathOperation<T> operation) throws IOException {
    return operateOnPrivateFile(STAGING_DIRECTORY, session.getStagingKey(), operation);
  }

  private <T> T operateOnStagingKey(String key, CheckedPathOperation<T> operation)
      throws IOException {
    return operateOnPrivateFile(STAGING_DIRECTORY, key, operation);
  }

  private <T> T operateOnQuarantine(String key, CheckedPathOperation<T> operation)
      throws IOException {
    return operateOnPrivateFile(QUARANTINE_DIRECTORY, key, operation);
  }

  private <T> T operateOnPrivateDirectory(String directory, CheckedPathOperation<T> operation)
      throws IOException {
    try {
      return privateBoundary.operateOnDirectory(directory, operation);
    } catch (BoundaryUnavailableException exception) {
      throw unavailable();
    }
  }

  private <T> T operateOnPrivateFile(
      String directory, String key, CheckedPathOperation<T> operation) throws IOException {
    try {
      return privateBoundary.operateOnFile(directory, key, operation);
    } catch (BoundaryUnavailableException exception) {
      throw unavailable();
    }
  }

  private void deleteStagingIfExists(SharedFolderUploadSession session) throws IOException {
    deleteStagingKeyIfExists(session.getStagingKey());
  }

  private void deleteStagingKeyIfExists(String key) throws IOException {
    operateOnStagingKey(key, staging -> {
      Files.deleteIfExists(staging);
      return null;
    });
  }

  private BasicFileAttributes stagingAttributes(SharedFolderUploadSession session)
      throws IOException {
    return operateOnStaging(session, staging -> Files.readAttributes(
        staging, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
  }

  private boolean stagingIdentityMatches(SharedFolderUploadSession session) throws IOException {
    return session.getFinalizingIdentity() != null
        && Objects.equals(session.getFinalizingIdentity(), portableIdentity(stagingAttributes(session)));
  }

  private void requireStagingSameFileStore(
      SharedFolderUploadSession session, Path visibleParent) throws IOException {
    operateOnStaging(session, staging -> {
      requireSameFileStore(staging, visibleParent);
      return null;
    });
  }

  private void requireStagingAndQuarantineSameFileStore(SharedFolderUploadSession session)
      throws IOException {
    operateOnStaging(session, staging -> operateOnQuarantine(
        session.getFinalizingQuarantineKey(), quarantine -> {
          requireSameFileStore(staging, quarantine.getParent());
          return null;
        }));
  }

  private void moveStagingToVisible(SharedFolderUploadSession session, Path target)
      throws IOException {
    operateOnStaging(session, staging -> {
      moveAtomically(staging, target, false);
      return null;
    });
  }

  private void moveVisibleToQuarantine(
      SharedFolderUploadSession session, Path observedTarget) throws IOException {
    operateOnQuarantine(session.getFinalizingQuarantineKey(), quarantine -> {
      moveAtomically(observedTarget, quarantine, false);
      return null;
    });
  }

  private boolean quarantineIdentityMatches(SharedFolderUploadSession session)
      throws IOException {
    return operateOnQuarantine(session.getFinalizingQuarantineKey(), quarantine ->
        portableReplacementIdentityMatches(
            quarantine, session.getFinalizingTargetIdentity(), session));
  }

  private boolean quarantineExists(String key) throws IOException {
    return operateOnQuarantine(key, quarantine ->
        Files.exists(quarantine, LinkOption.NOFOLLOW_LINKS));
  }

  private void deleteQuarantine(String key) throws IOException {
    operateOnQuarantine(key, quarantine -> {
      Files.delete(quarantine);
      return null;
    });
  }

  private void moveQuarantineToVisible(String key, Path target) throws IOException {
    operateOnQuarantine(key, quarantine -> {
      moveAtomically(quarantine, target, false);
      return null;
    });
  }

  private void requireReserve(Path staging, long neededBytes) throws IOException {
    long available = Files.getFileStore(staging).getUsableSpace();
    long reserve = properties.minimumFreeSpace().toBytes();
    if (neededBytes < 0 || available < reserve || available - reserve < neededBytes) {
      throw insufficientStorage();
    }
  }

  private void requireTargetToken(
      SharedFolderPathResolver resolver, SharedFolderUploadSession session, Path target) {
    BasicFileAttributes attributes = resolver.readHandle(target).attributes();
    String current = SharedFolderObservedItemTokens.token(relativePath(session), attributes);
    if (!constantTimeEquals(session.getTargetObservedToken(), current)) {
      throw conflict();
    }
  }

  private void requireSameFileStore(Path first, Path second) throws IOException {
    if (!Files.getFileStore(first).equals(Files.getFileStore(second))) {
      throw conflict();
    }
  }

  protected void moveAtomically(Path source, Path target, boolean replace) throws IOException {
    try {
      if (replace) {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } else {
        // ATOMIC_MOVE may replace an existing target even without REPLACE_EXISTING on Windows.
        Files.move(source, target);
      }
    } catch (AtomicMoveNotSupportedException exception) {
      throw conflict();
    }
  }

  private String relativePath(SharedFolderUploadSession session) {
    return session.getParentPath().isEmpty() ? session.getName()
        : session.getParentPath() + "/" + session.getName();
  }

  private String requiredAccountId(Account account) {
    if (account == null || account.getId() == null || account.getId().isBlank()) {
      throw conflict();
    }
    return account.getId();
  }

  private SharedFolderUploadStatus status(SharedFolderUploadSession session) {
    var committedChunks = session.getChunkLengths().entrySet().stream()
        .map(entry -> {
          long offset = Long.parseLong(entry.getKey());
          return new SharedFolderUploadChunkProof(
              offset, entry.getValue(), session.getChunkDigests().get(entry.getKey()));
        })
        .filter(proof -> proof.offset() >= 0 && proof.length() > 0 && proof.sha256() != null
            && proof.offset() + proof.length() <= session.getNextOffset())
        .sorted(java.util.Comparator.comparingLong(SharedFolderUploadChunkProof::offset))
        .toList();
    return new SharedFolderUploadStatus(session.getId(), session.getParentPath(), session.getName(),
        session.getExpectedBytes(), session.getNextOffset(), session.getState(), session.getExpiresAt(),
        properties.uploadChunk().toBytes(), committedChunks);
  }

  private String normalizeDigest(String digest) {
    if (digest == null || digest.isBlank()) {
      return null;
    }
    try {
      byte[] decoded = Base64.getUrlDecoder().decode(digest);
      if (decoded.length != 32) {
        throw conflict();
      }
      return Base64.getUrlEncoder().withoutPadding().encodeToString(decoded);
    } catch (IllegalArgumentException exception) {
      throw conflict();
    }
  }

  private MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required", exception);
    }
  }

  private String encodeDigest(byte[] digest) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
  }

  private boolean constantTimeEquals(String expected, String actual) {
    return expected != null && actual != null && MessageDigest.isEqual(
        expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
  }

  private ResponseStatusException conflict() {
    return new ResponseStatusException(HttpStatus.CONFLICT, "Shared-folder upload conflict");
  }

  private static final class FinalizationLeaseLostException extends ResponseStatusException {
    private FinalizationLeaseLostException() {
      super(HttpStatus.CONFLICT, "Shared-folder upload finalization lease was lost");
    }
  }

  private static final class AppendLeaseLostException extends ResponseStatusException {
    private AppendLeaseLostException() {
      super(HttpStatus.CONFLICT, "Shared-folder upload append lease was lost");
    }
  }

  private ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Shared-folder upload was not found");
  }

  private ResponseStatusException tooLarge() {
    return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Shared-folder upload chunk is too large");
  }

  private ResponseStatusException invalid() {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Shared-folder upload request is invalid");
  }

  private ResponseStatusException unavailable() {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
        "Shared-folder upload boundary is unavailable");
  }

  private ResponseStatusException insufficientStorage() {
    return new ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE,
        "Insufficient shared-folder storage");
  }

  private ResponseStatusException nativeFailure(
      NativeBoundaryException exception, boolean zeroMeansOperationConflict) {
    int status = exception.ntStatus();
    if (isNativeMissing(exception)) {
      return notFound();
    }
    if (status == 0xC0000035 || status == 0xC0000043 || status == 32
        || status == 80 || status == 183 || status == 0xC0000101
        || status == 0 && zeroMeansOperationConflict) {
      return conflict();
    }
    return unavailable();
  }

  private boolean isNativeMissing(NativeBoundaryException exception) {
    int status = exception.ntStatus();
    return status == 0xC0000034 || status == 0xC000003A || status == 2 || status == 3;
  }

  private record DigestAndLength(String digest, long length) {}

  private record Chunk(String key, String digest, long length) {}
}
