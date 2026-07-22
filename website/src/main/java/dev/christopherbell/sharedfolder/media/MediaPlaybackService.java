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
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Authorizes, classifies, admits, owns, and describes shared-folder media work. */
@Service
public class MediaPlaybackService {
  public static final int PROFILE_VERSION = 1;
  private static final Set<String> DIRECT_EXTENSIONS =
      Set.of("flac", "m4a", "mp3", "ogg", "wav", "mp4", "ogv", "webm");

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

  /** Classifies only known browser-native containers; browser errors may still request fallback. */
  public MediaPlaybackDescriptor playback(String path) {
    access.requireRead();
    sources.resolve(path);
    String extension = extension(path);
    return DIRECT_EXTENSIONS.contains(extension)
        ? MediaPlaybackDescriptor.direct() : MediaPlaybackDescriptor.fallbackRequired();
  }

  /** Creates a fixed-profile worker job or reuses a complete matching derivative. */
  public MediaPlaybackDescriptor requestFallback(String path, MediaOutputProfile profile) {
    String safePath = path == null ? "invalid-path" : path;
    try {
      Account account = access.requireRead();
      if (profile == null) throw badRequest("A fixed media output profile is required");
      MediaSourceSnapshot source = sources.resolve(path);
      String cacheKey = MediaCacheKeys.forSource(source, profile, PROFILE_VERSION);
      var ready = jobs.findFirstByCacheKeyAndStatusOrderByUpdatedAtDesc(
          cacheKey, MediaJobStatus.READY);
      if (ready.isPresent() && storage.readyExists(ready.get())) {
        storage.touchReady(ready.get());
        audit.recordFor(account, "MEDIA_CACHE_HIT", path, source.size(), "accepted", null);
        return MediaPlaybackDescriptor.from(ready.get());
      }

      synchronized (admissionLock) {
        requireCapacity(account.getId());
        requireDiskReserve(source);
        evictCache();
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
        job.setStatus(MediaJobStatus.QUEUED);
        job.setDeadline(now.plus(properties.jobTimeout()));
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        job.setLastAccessedAt(now);
        jobs.save(job);
        try {
          storage.writeDescriptor(descriptor(job, source));
        } catch (IOException failure) {
          job.setStatus(MediaJobStatus.FAILED);
          job.setFailureCategory("storage_unavailable");
          job.setUpdatedAt(clock.instant());
          jobs.save(job);
          throw unavailable();
        }
        audit.recordFor(account, "MEDIA_QUEUED", path, source.size(), "accepted", null);
        return MediaPlaybackDescriptor.from(job);
      }
    } catch (RuntimeException failure) {
      audit.recordFailureOnce("MEDIA_QUEUE_REJECTED", safePath, failure);
      throw failure;
    }
  }

  /** Returns a job only to its owner after refreshing read access. */
  public MediaPlaybackDescriptor job(String id) {
    Account account = access.requireRead();
    MediaJob job = refreshWorkerState(owned(id, account.getId()));
    return MediaPlaybackDescriptor.from(job);
  }

  /** Cancels one owned nonterminal job through an atomic repository transition. */
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
        job.setUpdatedAt(clock.instant());
        audit.recordFor(account, "MEDIA_CANCELED", job.getSourcePath(), job.getOutputBytes(),
            "accepted", null);
      }
    }
    return MediaPlaybackDescriptor.from(job);
  }

  /** Internal stream lookup that still performs fresh access and owner checks. */
  public MediaJob requireVisibleJob(String id) {
    Account account = access.requireRead();
    return refreshWorkerState(owned(id, account.getId()));
  }

  /** Reconciles one already-authorized job from the strict worker status file. */
  synchronized MediaJob refreshWorkerState(MediaJob job) {
    Instant now = clock.instant();
    if (!job.getStatus().terminal() && !job.getDeadline().isAfter(now)) {
      job.setStatus(MediaJobStatus.TIMED_OUT);
      job.setFailureCategory("timeout");
      job.setUpdatedAt(now);
      jobs.save(job);
      audit.recordSystem("MEDIA_TIMED_OUT", job.getSourcePath(), job.getOutputBytes(),
          "rejected", "timeout");
      return job;
    }
    var status = storage.readStatus(job, properties.maxOutput().toBytes());
    if (status.isEmpty() || status.get().status() == job.getStatus()
        || !validTransition(job.getStatus(), status.get().status())
        || (MediaJobStatus.processing().contains(status.get().status())
            && !MediaJobStatus.processing().contains(job.getStatus())
            && jobs.countByStatusIn(MediaJobStatus.processing()) > 0)
        || status.get().status() == MediaJobStatus.READY && !storage.readyExists(job)) {
      return job;
    }
    job.setStatus(status.get().status());
    job.setOutputBytes(status.get().outputBytes());
    job.setFailureCategory(status.get().failureCategory());
    job.setUpdatedAt(now);
    if (job.getStatus() == MediaJobStatus.READY) job.setLastAccessedAt(now);
    jobs.save(job);
    if (job.getStatus() == MediaJobStatus.READY) {
      storage.touchReady(job);
      evictCache(Set.of(job.getCacheKey()));
    }
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
    return job;
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

  private MediaJob owned(String id, String ownerId) {
    if (id == null || id.isBlank()) throw notFound();
    return jobs.findById(id)
        .filter(job -> ownerId.equals(job.getOwnerId()))
        .orElseThrow(this::notFound);
  }

  private void requireCapacity(String ownerId) {
    if (jobs.countByStatusIn(MediaJobStatus.active()) >= properties.queueCapacity()
        || jobs.countByOwnerIdAndStatusIn(ownerId, MediaJobStatus.active())
            >= properties.perAccountQueueCapacity()) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
          "The media queue is full. Try again later.");
    }
  }

  private void requireDiskReserve(MediaSourceSnapshot source) {
    try {
      long available = storage.usableSpace();
      long reserve = folderProperties.minimumFreeSpace().toBytes();
      long estimated = Math.min(source.size(), properties.maxOutput().toBytes());
      if (available < reserve || available - reserve < estimated) {
        throw new ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE,
            "Media conversion is paused to preserve disk space.");
      }
    } catch (IOException failure) {
      throw unavailable();
    }
  }

  private void evictCache() {
    evictCache(Set.of());
  }

  private void evictCache(Set<String> additionallyProtected) {
    try {
      Set<String> activeKeys = jobs.findByStatusIn(MediaJobStatus.active()).stream()
          .map(MediaJob::getCacheKey).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
      activeKeys.addAll(additionallyProtected);
      storage.evictToLimit(folderProperties.transcodeCacheLimit().toBytes(), activeKeys);
    } catch (IOException failure) {
      throw unavailable();
    }
  }

  private MediaWorkerJobDescriptor descriptor(MediaJob job, MediaSourceSnapshot source)
      throws IOException {
    return new MediaWorkerJobDescriptor(
        1, job.getId(), job.getCacheKey(), source.absolutePath(), storage.partialPath(job),
        storage.readyPath(job), storage.statusPath(job), storage.cancellationPath(job),
        source.size(), source.modifiedAt(),
        job.getProfile(), job.getDeadline(), properties.maxOutput().toBytes(),
        properties.initialBuffer().toBytes());
  }

  private String extension(String path) {
    int dot = path == null ? -1 : path.lastIndexOf('.');
    return dot < 0 || dot == path.length() - 1
        ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
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
}
