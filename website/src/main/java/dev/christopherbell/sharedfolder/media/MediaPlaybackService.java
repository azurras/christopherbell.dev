package dev.christopherbell.sharedfolder.media;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.SharedFolderMediaProperties;
import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRecorder;
import dev.christopherbell.sharedfolder.media.MediaSourceBoundary.MediaSourceSnapshot;
import dev.christopherbell.sharedfolder.security.SharedFolderAccessService;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Authorizes, admits, owns, reconciles, and safely schedules shared-folder media work. */
@Service
public class MediaPlaybackService {
  public static final int PROFILE_VERSION = 1;
  private static final int CACHE_PAGE_SIZE = 200;

  private final SharedFolderAccessService access;
  private final MediaJobRepository jobs;
  private final SharedFolderAuditRecorder audit;
  private final MediaSourceBoundary sources;
  private final MediaStorage storage;
  private final SharedFolderProperties folderProperties;
  private final SharedFolderMediaProperties properties;
  private final Clock clock;
  private final Supplier<String> ids;
  private final Object admissionLock = new Object();

  @Autowired
  public MediaPlaybackService(
      SharedFolderAccessService access,
      MediaJobRepository jobs,
      SharedFolderAuditRecorder audit,
      MediaSourceBoundary sources,
      MediaStorage storage,
      SharedFolderProperties folderProperties,
      SharedFolderMediaProperties properties,
      Clock clock) {
    this(access, jobs, audit, sources, storage, folderProperties, properties, clock,
        () -> UUID.randomUUID().toString());
  }

  MediaPlaybackService(
      SharedFolderAccessService access,
      MediaJobRepository jobs,
      SharedFolderAuditRecorder audit,
      MediaSourceBoundary sources,
      MediaStorage storage,
      SharedFolderProperties folderProperties,
      SharedFolderMediaProperties properties,
      Clock clock,
      Supplier<String> ids) {
    this.access = access;
    this.jobs = jobs;
    this.audit = audit;
    this.sources = sources;
    this.storage = storage;
    this.folderProperties = folderProperties;
    this.properties = properties;
    this.clock = clock;
    this.ids = ids;
  }

  /** Validates a direct browser probe without claiming extension-derived codec compatibility. */
  public MediaPlaybackDescriptor playback(String path) {
    Account account = access.requireRead();
    MediaSourceSnapshot source = sources.resolve(path);
    audit.recordFor(account, "MEDIA_DIRECT_PROBE", path, source.size(), "accepted", null);
    return MediaPlaybackDescriptor.directProbe();
  }

  /** Creates one fixed-profile worker job, reuses active work, or returns a shared ready cache. */
  public MediaPlaybackDescriptor requestFallback(String path, MediaOutputProfile profile) {
    String safePath = path == null ? "invalid-path" : path;
    try {
      Account account = access.requireRead();
      if (profile == null) throw badRequest("A fixed media output profile is required");
      MediaSourceSnapshot source = sources.resolve(path);
      String cacheKey = MediaCacheKeys.forSource(source, profile, PROFILE_VERSION);
      synchronized (admissionLock) {
        MediaJob ready = readyCache(cacheKey);
        if (ready != null) {
          ready.setLastAccessedAt(clock.instant());
          ready.setUpdatedAt(clock.instant());
          jobs.save(ready);
          audit.recordFor(account, "MEDIA_CACHE_HIT", path, source.size(), "accepted", null);
          return MediaPlaybackDescriptor.from(ready);
        }
        var existing = jobs.findFirstByCacheKeyAndStatusInOrderByCreatedAtAsc(
            cacheKey, MediaJobStatus.active());
        if (existing.isPresent()) {
          if (Objects.equals(existing.get().getOwnerId(), account.getId())) {
            return MediaPlaybackDescriptor.from(existing.get());
          }
          throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
              "This media is already being prepared. Try again shortly.");
        }

        requireCapacity(account.getId());
        long reservedBeforeAdmission = activeReservations();
        long reservation = properties.maxOutput().toBytes();
        evictCache(reservedBeforeAdmission, reservation, Set.of());
        requireDiskReserve(reservedBeforeAdmission, reservation);
        Instant now = clock.instant();
        MediaJob job = new MediaJob();
        job.setId(ids.get());
        job.setOwnerId(account.getId());
        job.setSourcePath(source.relativePath());
        job.setSourceSize(source.size());
        job.setSourceModifiedAt(source.modifiedAt());
        job.setProfile(profile);
        job.setProfileVersion(PROFILE_VERSION);
        job.setCacheKey(cacheKey);
        job.setActiveCacheKey(cacheKey);
        job.setStatus(MediaJobStatus.QUEUED);
        job.setReservedBytes(reservation);
        job.setDeadline(now.plus(properties.jobTimeout()));
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        job.setLastAccessedAt(now);
        try {
          jobs.save(job);
        } catch (DuplicateKeyException duplicate) {
          MediaJob winner = jobs.findFirstByCacheKeyAndStatusInOrderByCreatedAtAsc(
              cacheKey, MediaJobStatus.active()).orElseThrow(() -> duplicate);
          if (Objects.equals(winner.getOwnerId(), account.getId())) {
            return MediaPlaybackDescriptor.from(winner);
          }
          throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
              "This media is already being prepared. Try again shortly.");
        }
        publishIfIdle(job, source);
        audit.recordFor(account, "MEDIA_QUEUED", path, source.size(), "accepted", null);
        return MediaPlaybackDescriptor.from(job);
      }
    } catch (RuntimeException failure) {
      audit.recordFailureOnce("MEDIA_QUEUE_REJECTED", safePath, failure);
      throw failure;
    }
  }

  public MediaPlaybackDescriptor job(String id) {
    Account account = access.requireRead();
    return MediaPlaybackDescriptor.from(refreshWorkerState(visible(id, account.getId())));
  }

  public MediaPlaybackDescriptor cancel(String id) {
    Account account = access.requireRead();
    MediaJob job = owned(id, account.getId());
    if (!job.getStatus().terminal()) {
      try {
        storage.requestCancellation(job);
      } catch (IOException failure) {
        throw unavailable();
      }
      if (jobs.cancelActive(id, account.getId(), clock.instant()) != 1) {
        job = owned(id, account.getId());
      } else {
        job.setStatus(MediaJobStatus.CANCELED);
        job.setActiveCacheKey(null);
        job.setDescriptorPublished(false);
        job.setUpdatedAt(clock.instant());
        audit.recordFor(account, "MEDIA_CANCELED", job.getSourcePath(), job.getOutputBytes(),
            "accepted", null);
        publishNextIfIdle();
      }
    }
    return MediaPlaybackDescriptor.from(job);
  }

  public MediaJob requireVisibleJob(String id) {
    Account account = access.requireRead();
    return refreshWorkerState(visible(id, account.getId()));
  }

  /** Reconciles one already-authorized job from a strict status document and actual output size. */
  synchronized MediaJob refreshWorkerState(MediaJob job) {
    Instant now = clock.instant();
    if (job.getStatus() == MediaJobStatus.READY
        && !storage.readyMatches(job, job.getOutputBytes(), properties.maxOutput().toBytes())) {
      return job;
    }
    if (!job.getStatus().terminal() && !job.getDeadline().isAfter(now)) {
      terminal(job, MediaJobStatus.TIMED_OUT, "timeout", now);
      audit.recordSystem("MEDIA_TIMED_OUT", job.getSourcePath(), job.getOutputBytes(),
          "rejected", "timeout");
      publishNextIfIdle();
      return job;
    }
    var status = storage.readStatus(job, properties.maxOutput().toBytes());
    if (status.isEmpty() || status.get().status() == job.getStatus()
        || !validTransition(job.getStatus(), status.get().status())
        || (MediaJobStatus.processing().contains(status.get().status())
            && !job.isDescriptorPublished())
        || status.get().status() == MediaJobStatus.READY
            && !storage.readyMatches(
                job, status.get().outputBytes(), properties.maxOutput().toBytes())) {
      return job;
    }
    job.setStatus(status.get().status());
    job.setOutputBytes(status.get().outputBytes());
    job.setFailureCategory(status.get().failureCategory());
    job.setUpdatedAt(now);
    if (job.getStatus().terminal()) {
      job.setActiveCacheKey(null);
      job.setDescriptorPublished(false);
      if (job.getStatus() == MediaJobStatus.READY) job.setLastAccessedAt(now);
    }
    jobs.save(job);
    String action = switch (job.getStatus()) {
      case INSPECTING -> "MEDIA_INSPECTING";
      case TRANSCODING -> "MEDIA_TRANSCODING";
      case BUFFERING -> "MEDIA_BUFFERING";
      case READY -> "MEDIA_READY";
      case CANCELED -> "MEDIA_CANCELED";
      case INSUFFICIENT_SPACE -> "MEDIA_SPACE_REJECTED";
      case TIMED_OUT -> "MEDIA_TIMED_OUT";
      case FAILED -> "MEDIA_FAILED";
      case QUEUED -> "MEDIA_QUEUED";
    };
    audit.recordSystem(action, job.getSourcePath(), job.getOutputBytes(),
        job.getStatus() == MediaJobStatus.READY ? "accepted" : "progress",
        job.getFailureCategory());
    if (job.getStatus().terminal()) {
      if (job.getStatus() == MediaJobStatus.READY) {
        evictCache(activeReservations(), 0, Set.of(job.getCacheKey()));
      }
      publishNextIfIdle();
    }
    return job;
  }

  /** Restart-safe scheduler publishes at most one queued descriptor at a time. */
  @Scheduled(fixedDelayString = "${app.shared-folder.media.admission-poll:PT5S}")
  public void promoteQueuedJob() {
    synchronized (admissionLock) {
      publishNextIfIdle();
    }
  }

  private void publishNextIfIdle() {
    if (jobs.countByDescriptorPublishedTrueAndStatusIn(MediaJobStatus.active()) > 0) return;
    while (true) {
      MediaJob next = jobs.findFirstByStatusAndDescriptorPublishedFalseOrderByCreatedAtAsc(
          MediaJobStatus.QUEUED).orElse(null);
      if (next == null) return;
      try {
        MediaSourceSnapshot source = sources.resolve(next.getSourcePath());
        String currentKey = MediaCacheKeys.forSource(source, next.getProfile(), next.getProfileVersion());
        if (!currentKey.equals(next.getCacheKey())) {
          terminal(next, MediaJobStatus.FAILED, "source_changed", clock.instant());
          continue;
        }
        publish(next, source);
        return;
      } catch (IOException | RuntimeException failure) {
        terminal(next, MediaJobStatus.FAILED, "storage_unavailable", clock.instant());
        audit.recordSystemFailure("MEDIA_PUBLISH_FAILED", next.getSourcePath(), null,
            failure instanceof RuntimeException runtime ? runtime : unavailable());
      }
    }
  }

  private void publishIfIdle(MediaJob job, MediaSourceSnapshot source) {
    if (jobs.countByDescriptorPublishedTrueAndStatusIn(MediaJobStatus.active()) == 0) {
      try {
        publish(job, source);
      } catch (IOException failure) {
        terminal(job, MediaJobStatus.FAILED, "storage_unavailable", clock.instant());
        throw unavailable();
      }
    }
  }

  private void publish(MediaJob job, MediaSourceSnapshot source) throws IOException {
    job.setDescriptorPublished(true);
    job.setUpdatedAt(clock.instant());
    jobs.save(job);
    storage.writeDescriptor(descriptor(job, source));
  }

  private MediaJob readyCache(String cacheKey) {
    return jobs.findFirstByCacheKeyAndStatusOrderByUpdatedAtDesc(cacheKey, MediaJobStatus.READY)
        .filter(job -> storage.readyMatches(
            job, job.getOutputBytes(), properties.maxOutput().toBytes()))
        .orElse(null);
  }

  private void requireCapacity(String ownerId) {
    if (jobs.countByStatusIn(MediaJobStatus.active()) >= properties.queueCapacity()
        || jobs.countByOwnerIdAndStatusIn(ownerId, MediaJobStatus.active())
            >= properties.perAccountQueueCapacity()) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
          "The media queue is full. Try again later.");
    }
  }

  private long activeReservations() {
    try {
      long total = 0;
      for (MediaJob active : jobs.findByStatusIn(MediaJobStatus.active())) {
        total = Math.addExact(total, active.getReservedBytes());
      }
      return total;
    } catch (ArithmeticException exception) {
      throw insufficientStorage();
    }
  }

  private void requireDiskReserve(long activeReservations, long newReservation) {
    try {
      long required = Math.addExact(folderProperties.minimumFreeSpace().toBytes(),
          Math.addExact(activeReservations, newReservation));
      if (storage.usableSpace() < required) {
        throw insufficientStorage();
      }
    } catch (ArithmeticException exception) {
      throw insufficientStorage();
    } catch (IOException failure) {
      throw unavailable();
    }
  }

  /** Evicts oldest completed caches by persisted access time before reserve admission. */
  private void evictCache(
      long activeReservations, long newReservation, Set<String> additionallyProtected) {
    try {
      Set<String> protectedKeys = new HashSet<>(additionallyProtected);
      jobs.findByStatusIn(MediaJobStatus.active()).stream().map(MediaJob::getCacheKey)
          .filter(Objects::nonNull).forEach(protectedKeys::add);
      long total = cacheBytes();
      long requiredFree = Math.addExact(folderProperties.minimumFreeSpace().toBytes(),
          Math.addExact(activeReservations, newReservation));
      int page = 0;
      boolean more;
      do {
        var slice = jobs.findByStatusOrderByLastAccessedAtAscIdAsc(
            MediaJobStatus.READY, PageRequest.of(page++, CACHE_PAGE_SIZE));
        for (MediaJob candidate : slice.getContent()) {
          long length = storage.readyLength(candidate, Long.MAX_VALUE);
          if (length < 0 || protectedKeys.contains(candidate.getCacheKey())
              || storage.isBeingRead(candidate.getCacheKey())) continue;
          if (total <= folderProperties.transcodeCacheLimit().toBytes()
              && storage.usableSpace() >= requiredFree) return;
          if (storage.deleteReady(candidate)) total = Math.max(0, total - length);
        }
        more = slice.hasNext();
      } while (more);
    } catch (ArithmeticException | IOException failure) {
      throw unavailable();
    }
  }

  private long cacheBytes() throws IOException {
    long total = 0;
    int page = 0;
    boolean more;
    do {
      var slice = jobs.findByStatusOrderByLastAccessedAtAscIdAsc(
          MediaJobStatus.READY, PageRequest.of(page++, CACHE_PAGE_SIZE));
      for (MediaJob ready : slice.getContent()) {
        long length = storage.readyLength(ready, Long.MAX_VALUE);
        if (length > 0) total = Math.addExact(total, length);
      }
      more = slice.hasNext();
    } while (more);
    return total;
  }

  private void terminal(
      MediaJob job, MediaJobStatus status, String failureCategory, Instant now) {
    job.setStatus(status);
    job.setFailureCategory(failureCategory);
    job.setActiveCacheKey(null);
    job.setDescriptorPublished(false);
    job.setUpdatedAt(now);
    jobs.save(job);
  }

  private boolean validTransition(MediaJobStatus from, MediaJobStatus to) {
    if (from.terminal()) return false;
    return switch (from) {
      case QUEUED -> to != MediaJobStatus.QUEUED;
      case INSPECTING -> to != MediaJobStatus.QUEUED && to != MediaJobStatus.INSPECTING;
      case TRANSCODING -> to == MediaJobStatus.BUFFERING || to.terminal();
      case BUFFERING -> to.terminal();
      default -> false;
    };
  }

  private MediaJob visible(String id, String accountId) {
    if (id == null || id.isBlank()) throw notFound();
    return jobs.findById(id)
        .filter(job -> accountId.equals(job.getOwnerId()) || job.getStatus() == MediaJobStatus.READY)
        .orElseThrow(this::notFound);
  }

  private MediaJob owned(String id, String ownerId) {
    if (id == null || id.isBlank()) throw notFound();
    return jobs.findById(id).filter(job -> ownerId.equals(job.getOwnerId()))
        .orElseThrow(this::notFound);
  }

  private MediaWorkerJobDescriptor descriptor(MediaJob job, MediaSourceSnapshot source)
      throws IOException {
    return new MediaWorkerJobDescriptor(
        1, job.getId(), job.getCacheKey(), source.absolutePath(), storage.partialPath(job),
        storage.readyPath(job), storage.statusPath(job), storage.cancellationPath(job),
        source.size(), source.modifiedAt(), job.getProfile(), job.getDeadline(),
        properties.maxOutput().toBytes(), properties.initialBuffer().toBytes());
  }

  private ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Media job was not found");
  }

  private ResponseStatusException unavailable() {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
        "Media conversion is temporarily unavailable");
  }

  private ResponseStatusException insufficientStorage() {
    return new ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE,
        "Media conversion is paused to preserve disk space.");
  }
}
