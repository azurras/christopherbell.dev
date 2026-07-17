package dev.christopherbell.sharedfolder.upload;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.configuration.filter.RequestPayloadTooLargeException;
import dev.christopherbell.sharedfolder.fs.SharedFolderPathResolver;
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
  private static final Duration SESSION_TTL = Duration.ofHours(24);
  private static final Duration APPEND_LEASE_TTL = Duration.ofMinutes(2);
  private static final int COPY_BUFFER_SIZE = 16 * 1024;

  private final SharedFolderAccessService access;
  private final SharedFolderUploadSessionRepository sessions;
  private final SharedFolderProperties properties;
  private final WindowsSharedFolderMutationBoundary nativeBoundary;
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
      SharedFolderPathResolver resolver = new SharedFolderPathResolver(properties.root());
      resolver.newChild(request.parentPath(), request.name());
      Path staging = stagingDirectory();
      requireReserve(staging, request.expectedBytes());
      Instant now = Instant.now();
      SharedFolderUploadSession session = new SharedFolderUploadSession();
      session.setId(UUID.randomUUID().toString());
      session.setOwnerId(requiredAccountId(account));
      session.setParentPath(request.parentPath());
      session.setName(request.name());
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
    } catch (IOException | SecurityException exception) {
      throw conflict();
    }
  }

  /** Streams one ordered chunk with a SHA-256 check; identical ordered retries are idempotent. */
  public SharedFolderUploadStatus append(
      String id, long offset, InputStream body, String suppliedDigest) {
    Account account = access.requireWrite();
    String expectedDigest = normalizeDigest(suppliedDigest);
    requireEnabled();
    SharedFolderUploadSession session = ownedForAppend(id, account, offset, expectedDigest);
    if (nativeBoundary.nativeMode()) {
      return appendNative(session, account, offset, body, expectedDigest);
    }
    Path staging;
    try {
      staging = stagingDirectory();
      requireReserve(staging, session.getExpectedBytes() - session.getNextOffset());
      if (offset < session.getNextOffset()) {
        return verifyRetry(session, offset, body, expectedDigest);
      }
      if (offset != session.getNextOffset()) {
        throw conflict();
      }
      Chunk chunk = stageChunk(staging, session, offset, body, expectedDigest);
      boolean appendStarted = false;
      try {
        try {
          session = acquireAppendLease(session, account, offset, chunk.digest(), chunk.length(), null);
        } catch (ResponseStatusException leaseConflict) {
          return status(awaitMatchingCommittedChunk(
              session.getId(), account, offset, chunk.digest(), chunk.length()));
        }
        reconcilePortableAppendLength(stagingPath(staging, session), offset);
        appendStarted = true;
        appendStagedChunk(stagingPath(staging, session), chunk.path());
        recordChunk(session, offset, chunk.digest(), chunk.length());
        clearAppendLease(session);
        session.setUpdatedAt(Instant.now());
        try {
          return status(sessions.save(session));
        } catch (RuntimeException exception) {
          throw conflict();
        }
      } catch (RuntimeException | IOException exception) {
        if (appendStarted) {
          rollbackPortableAppend(stagingPath(staging, session), offset);
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
        Files.deleteIfExists(chunk.path());
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
      } catch (NativeBoundaryException | SecurityException exception) {
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
    try {
      Files.deleteIfExists(stagingPath(stagingDirectory(), session));
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
      Path staging = stagingDirectory();
      Path stagedFile = stagingPath(staging, session);
      verifyCompleteDigest(session, stagedFile);
      SharedFolderPathResolver resolver = new SharedFolderPathResolver(properties.root());
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
      }
      requireSameFileStore(stagedFile, target.getParent());
      if (session.getState() == SharedFolderUploadState.ACTIVE) {
        BasicFileAttributes stagedAttributes = Files.readAttributes(
            stagedFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        session = beginFinalizing(
            session,
            portableIdentity(stagedAttributes),
            replace,
            existingTarget == null ? null : portableIdentity(Files.readAttributes(
                existingTarget, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)));
        if (replace) {
          afterPhysicalFinalizationTransition(SharedFolderUploadFinalizationState.PREPARED);
        }
      }
      if (replace) {
        session = finalizePortableReplacement(session, stagedFile, target, existingTarget);
      } else {
        moveAtomically(stagedFile, target, false);
      }
      session.setState(SharedFolderUploadState.COMPLETED);
      session.setUpdatedAt(Instant.now());
      try {
        return status(sessions.save(session));
      } catch (RuntimeException exception) {
        throw conflict();
      }
    } catch (ResponseStatusException exception) {
      throw exception;
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
      requireNativeReserve(request.expectedBytes());
      nativeBoundary.createStaging(stagingKey).close();
      staged = true;
      Instant now = Instant.now();
      SharedFolderUploadSession session = new SharedFolderUploadSession();
      session.setId(UUID.randomUUID().toString());
      session.setOwnerId(requiredAccountId(account));
      session.setParentPath(request.parentPath());
      session.setName(request.name());
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
        appendStarted = true;
        appendNativeChunk(session, chunkKey, offset);
      } catch (RuntimeException exception) {
        if (appendStarted) {
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
      recordChunk(session, offset, chunk.digest(), chunk.length());
      clearAppendLease(session);
      session.setUpdatedAt(Instant.now());
      try {
        return status(sessions.save(session));
      } catch (RuntimeException exception) {
        rollbackNativeAppend(session.getStagingKey(), offset);
        forgetChunk(session, offset);
        clearAppendLease(session);
        try {
          sessions.save(session);
        } catch (RuntimeException ignored) {
          // Another instance will recover the expired lease and physical offset.
        }
        throw conflict();
      }
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (RequestPayloadTooLargeException exception) {
      throw tooLarge();
    } catch (IOException | ArithmeticException | NativeBoundaryException | SecurityException exception) {
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
      for (int count; (count = source.read(buffer, 0, buffer.length)) != -1;) {
        writeFully(target, buffer, count);
      }
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
    current.setAppendLeaseExpiresAt(Instant.now().plus(APPEND_LEASE_TTL));
    current.setAppendOffset(offset);
    current.setAppendLength(length);
    current.setAppendDigest(digest);
    current.setAppendChunkKey(chunkKey);
    current.setUpdatedAt(Instant.now());
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

  private void reconcilePortableAppendLength(Path stagingFile, long committedOffset)
      throws IOException {
    if (Files.notExists(stagingFile, LinkOption.NOFOLLOW_LINKS)) {
      if (committedOffset == 0) {
        return;
      }
      throw conflict();
    }
    long size = Files.size(stagingFile);
    if (size < committedOffset) {
      throw conflict();
    }
    if (size > committedOffset) {
      rollbackPortableAppend(stagingFile, committedOffset);
    }
  }

  private SharedFolderUploadSession reconcileExpiredAppendLease(
      SharedFolderUploadSession session) {
    if (session.getState() != SharedFolderUploadState.APPENDING
        || session.getAppendLeaseExpiresAt() != null
            && session.getAppendLeaseExpiresAt().isAfter(Instant.now())) {
      return session;
    }
    try {
      if (nativeBoundary.nativeMode()) {
        rollbackNativeAppend(session.getStagingKey(), session.getNextOffset());
        if (session.getAppendChunkKey() != null) {
          try {
            nativeBoundary.deleteStaging(session.getAppendChunkKey());
          } catch (RuntimeException ignored) {
            // The random private chunk is harmless and remains eligible for bounded cleanup.
          }
        }
      } else {
        reconcilePortableAppendLength(
            stagingPath(stagingDirectory(), session), session.getNextOffset());
        if (session.getAppendChunkKey() != null) {
          Files.deleteIfExists(stagingDirectory().resolve(session.getAppendChunkKey()));
        }
      }
      clearAppendLease(session);
      session.setUpdatedAt(Instant.now());
      return sessions.save(session);
    } catch (IOException | RuntimeException exception) {
      throw conflict();
    }
  }

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

  private void rollbackPortableAppend(Path stagingFile, long offset) {
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
          if (replace) {
            afterPhysicalFinalizationTransition(SharedFolderUploadFinalizationState.PREPARED);
          }
        }
      }
      if (replace) {
        session = finalizeNativeReplacement(session, target);
      } else {
        nativeBoundary.finalizeStaging(
            session.getStagingKey(), session.getParentPath(), session.getName(), false, null);
      }
      session.setState(SharedFolderUploadState.COMPLETED);
      session.setUpdatedAt(Instant.now());
      try {
        return status(sessions.save(session));
      } catch (RuntimeException exception) {
        throw conflict();
      }
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (IOException | NativeBoundaryException | SecurityException exception) {
      throw conflict();
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
    session.setState(SharedFolderUploadState.FINALIZING);
    session.setUpdatedAt(Instant.now());
    try {
      return sessions.save(session);
    } catch (RuntimeException exception) {
      session.setState(SharedFolderUploadState.ACTIVE);
      session.setFinalizingIdentity(null);
      session.setFinalizingReplace(null);
      session.setFinalizingTargetIdentity(null);
      session.setFinalizingQuarantineKey(null);
      session.setFinalizationState(null);
      throw conflict();
    }
  }

  private SharedFolderUploadSession finalizePortableReplacement(
      SharedFolderUploadSession session, Path stagedFile, Path target, Path observedTarget)
      throws IOException {
    Path quarantine = uploadQuarantineDirectory().resolve(session.getFinalizingQuarantineKey());
    if (observedTarget != null) {
      requireSameFileStore(stagedFile, quarantine.getParent());
      moveAtomically(observedTarget, quarantine, false);
      if (!portableIdentityMatches(quarantine, session.getFinalizingTargetIdentity())) {
        throw conflict();
      }
    }
    afterPhysicalFinalizationTransition(SharedFolderUploadFinalizationState.TARGET_QUARANTINED);
    session = saveFinalizationPhase(session, SharedFolderUploadFinalizationState.TARGET_QUARANTINED);
    moveAtomically(stagedFile, target, false);
    afterPhysicalFinalizationTransition(SharedFolderUploadFinalizationState.SOURCE_MOVED);
    session = saveFinalizationPhase(session, SharedFolderUploadFinalizationState.SOURCE_MOVED);
    Files.delete(quarantine);
    return session;
  }

  private SharedFolderUploadSession finalizeNativeReplacement(
      SharedFolderUploadSession session, NativeFileMetadata expectedTarget) {
    NativeFileMetadata quarantined = nativeBoundary.quarantineVisible(
        relativePath(session), session.getFinalizingQuarantineKey(), expectedTarget);
    if (!Objects.equals(session.getFinalizingTargetIdentity(), nativeIdentity(quarantined))) {
      throw conflict();
    }
    afterPhysicalFinalizationTransition(SharedFolderUploadFinalizationState.TARGET_QUARANTINED);
    session = saveFinalizationPhase(session, SharedFolderUploadFinalizationState.TARGET_QUARANTINED);
    nativeBoundary.finalizeStaging(
        session.getStagingKey(), session.getParentPath(), session.getName(), false, null);
    afterPhysicalFinalizationTransition(SharedFolderUploadFinalizationState.SOURCE_MOVED);
    session = saveFinalizationPhase(session, SharedFolderUploadFinalizationState.SOURCE_MOVED);
    nativeBoundary.deleteQuarantine(session.getFinalizingQuarantineKey(), quarantined);
    return session;
  }

  private SharedFolderUploadSession saveFinalizationPhase(
      SharedFolderUploadSession session, SharedFolderUploadFinalizationState state) {
    session.setFinalizationState(state);
    session.setUpdatedAt(Instant.now());
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

  private SharedFolderUploadSession reconcileReplacementFinalization(
      SharedFolderUploadSession session) {
    try {
      if (nativeBoundary.nativeMode()) {
        return reconcileNativeReplacementFinalization(session);
      }
      Path target = properties.root().toAbsolutePath().normalize().resolve(relativePath(session));
      Path staging = stagingPath(stagingDirectory(), session);
      Path quarantine = uploadQuarantineDirectory().resolve(session.getFinalizingQuarantineKey());
      boolean sourceAtTarget = portableIdentityMatches(target, session.getFinalizingIdentity());
      boolean sourceAtStaging = portableIdentityMatches(staging, session.getFinalizingIdentity());
      boolean originalAtTarget = portableIdentityMatches(
          target, session.getFinalizingTargetIdentity());
      boolean originalAtQuarantine = portableIdentityMatches(
          quarantine, session.getFinalizingTargetIdentity());
      if (sourceAtTarget) {
        if (originalAtQuarantine) Files.delete(quarantine);
        return transition(session, SharedFolderUploadState.COMPLETED);
      }
      if (sourceAtStaging && originalAtTarget && Files.notExists(quarantine)) {
        return transition(session, SharedFolderUploadState.ACTIVE);
      }
      if (sourceAtStaging && Files.notExists(target) && originalAtQuarantine) {
        moveAtomically(quarantine, target, false);
        return transition(session, SharedFolderUploadState.ACTIVE);
      }
      return markRestorePending(session);
    } catch (RuntimeException | IOException exception) {
      return markRestorePending(session);
    }
  }

  private SharedFolderUploadSession reconcileNativeReplacementFinalization(
      SharedFolderUploadSession session) {
    NativeFileMetadata target = nativeMetadataOrNull(relativePath(session));
    NativeFileMetadata staging = nativeStagingMetadataOrNull(session.getStagingKey());
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
          nativeBoundary.deleteQuarantine(session.getFinalizingQuarantineKey(), quarantine);
        }
        return transition(session, SharedFolderUploadState.COMPLETED);
      }
      if (sourceAtStaging && originalAtTarget && quarantine == null) {
        return transition(session, SharedFolderUploadState.ACTIVE);
      }
      if (sourceAtStaging && target == null && originalAtQuarantine) {
        nativeBoundary.restoreQuarantine(
            session.getFinalizingQuarantineKey(), session.getParentPath(), session.getName(),
            quarantine);
        return transition(session, SharedFolderUploadState.ACTIVE);
      }
      return markRestorePending(session);
    } catch (RuntimeException exception) {
      return markRestorePending(session);
    }
  }

  private SharedFolderUploadSession markRestorePending(SharedFolderUploadSession session) {
    session.setFinalizationState(SharedFolderUploadFinalizationState.RESTORE_PENDING);
    session.setUpdatedAt(Instant.now());
    try {
      return sessions.save(session);
    } catch (RuntimeException ignored) {
      return session;
    }
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
      SharedFolderPathResolver resolver = new SharedFolderPathResolver(properties.root());
      return Objects.equals(session.getFinalizingIdentity(), portableIdentity(Files.readAttributes(
          resolver.existing(relativePath(session)), BasicFileAttributes.class,
          LinkOption.NOFOLLOW_LINKS)));
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
      BasicFileAttributes attributes = Files.readAttributes(
          stagingPath(stagingDirectory(), session), BasicFileAttributes.class,
          LinkOption.NOFOLLOW_LINKS);
      return Objects.equals(session.getFinalizingIdentity(), portableIdentity(attributes));
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
      return Files.isRegularFile(
          stagingPath(stagingDirectory(), session), LinkOption.NOFOLLOW_LINKS);
    } catch (RuntimeException | IOException exception) {
      return false;
    }
  }

  private boolean deletePendingStaging(SharedFolderUploadSession session) {
    try {
      if (nativeBoundary.nativeMode()) {
        nativeBoundary.deleteStagingIfExists(session.getStagingKey());
      } else {
        Files.deleteIfExists(stagingPath(stagingDirectory(), session));
      }
      return true;
    } catch (RuntimeException | IOException exception) {
      return false;
    }
  }

  private Path uploadQuarantineDirectory() throws IOException {
    Path root = Files.createDirectories(properties.systemRoot().toAbsolutePath().normalize()
        .resolve("shared-folder-upload-quarantine"));
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
      throw new IOException("private upload quarantine is unavailable");
    }
    return root;
  }

  private boolean portableIdentityMatches(Path path, String expected) throws IOException {
    return expected != null && Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        && Objects.equals(expected, portableIdentity(Files.readAttributes(
            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)));
  }

  private SharedFolderUploadSession transition(
      SharedFolderUploadSession session, SharedFolderUploadState state) {
    SharedFolderUploadState previous = session.getState();
    session.setState(state);
    session.setUpdatedAt(Instant.now());
    try {
      return sessions.save(session);
    } catch (RuntimeException exception) {
      session.setState(previous);
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
      Path staging, SharedFolderUploadSession session, long offset, InputStream body, String suppliedDigest)
      throws IOException {
    Path temporary = staging.resolve(session.getStagingKey() + ".chunk-" + offset + "-" + UUID.randomUUID());
    try {
      DigestAndLength digest = writeBounded(body, temporary, properties.uploadChunk().toBytes(),
          session.getExpectedBytes() - session.getNextOffset());
      if (digest.length() == 0 || !constantTimeEquals(digest.digest(), suppliedDigest)) {
        throw conflict();
      }
      return new Chunk(temporary, digest.digest(), digest.length());
    } catch (RuntimeException | IOException exception) {
      Files.deleteIfExists(temporary);
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

  protected void appendStagedChunk(Path stagingFile, Path chunk) throws IOException {
    try (InputStream input = Files.newInputStream(chunk);
        OutputStream output = Files.newOutputStream(stagingFile, StandardOpenOption.CREATE,
            StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
      input.transferTo(output);
    }
  }

  private void verifyCompleteDigest(SharedFolderUploadSession session, Path stagingFile) throws IOException {
    if (!Files.isRegularFile(stagingFile, LinkOption.NOFOLLOW_LINKS)
        || Files.size(stagingFile) != session.getExpectedBytes()) {
      throw conflict();
    }
    if (session.getExpectedSha256() == null) {
      return;
    }
    DigestAndLength digest = digestBounded(Files.newInputStream(stagingFile), session.getExpectedBytes(),
        session.getExpectedBytes());
    if (!constantTimeEquals(session.getExpectedSha256(), digest.digest())) {
      throw conflict();
    }
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

  private void requireEnabled() {
    if (!properties.enabled()) {
      throw unavailable();
    }
  }

  private Path stagingDirectory() throws IOException {
    Path systemRoot = properties.systemRoot().toAbsolutePath().normalize();
    return Files.createDirectories(systemRoot.resolve("shared-folder-upload-staging"));
  }

  private Path stagingPath(Path stagingDirectory, SharedFolderUploadSession session) {
    return stagingDirectory.resolve(session.getStagingKey());
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

  private record DigestAndLength(String digest, long length) {}

  private record Chunk(Path path, String digest, long length) {}
}
