package dev.christopherbell.sharedfolder.media;

import dev.christopherbell.configuration.SharedFolderMediaProperties;
import dev.christopherbell.sharedfolder.media.MediaStorage.MediaStoredFile;
import dev.christopherbell.sharedfolder.service.SharedFolderRangeNotSatisfiableException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpRange;
import org.springframework.stereotype.Component;

/** Sequentially streams a growing derivative with bounded polling and no whole-file buffering. */
@Component
public final class ProgressiveMediaStreamer {
  private static final int COPY_BUFFER_BYTES = 64 * 1024;
  private final MediaStorage storage;
  private final MediaJobRepository jobs;
  private final SharedFolderMediaProperties properties;
  private final MediaPlaybackService media;

  @Autowired
  public ProgressiveMediaStreamer(
      MediaStorage storage,
      MediaJobRepository jobs,
      SharedFolderMediaProperties properties,
      MediaPlaybackService media) {
    this.storage = storage;
    this.jobs = jobs;
    this.properties = properties;
    this.media = media;
  }

  ProgressiveMediaStreamer(
      MediaStorage storage, MediaJobRepository jobs, SharedFolderMediaProperties properties) {
    this.storage = storage;
    this.jobs = jobs;
    this.properties = properties;
    this.media = null;
  }

  public void copyGrowing(MediaJob original, OutputStream output) throws IOException {
    long position = 0;
    boolean started = false;
    long idleSince = System.nanoTime();
    ByteBuffer buffer = ByteBuffer.allocate(COPY_BUFFER_BYTES);
    while (!Thread.currentThread().isInterrupted()) {
      MediaJob current = jobs.findById(original.getId()).orElse(original);
      if (media != null) current = media.refreshWorkerState(current);
      MediaJob observed = current;
      MediaJobStatus effectiveStatus = storage
          .readStatus(observed, properties.maxOutput().toBytes())
          .map(MediaStorage.MediaWorkerStatus::status)
          .filter(status -> status != MediaJobStatus.READY || storage.readyExists(observed))
          .orElse(observed.getStatus());
      Path path = outputPath(observed, effectiveStatus);
      long size = ordinarySize(path);
      if (!started && size >= properties.initialBuffer().toBytes()) started = true;
      if (!started && effectiveStatus.terminal()) started = size > 0;
      if (started && size > position) {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
          channel.position(position);
          while (position < size) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), size - position));
            int read = channel.read(buffer);
            if (read <= 0) break;
            output.write(buffer.array(), 0, read);
            position += read;
          }
        }
        output.flush();
        idleSince = System.nanoTime();
      }
      if (effectiveStatus.terminal() && position >= size) return;
      if (Duration.ofNanos(System.nanoTime() - idleSince)
          .compareTo(properties.progressiveIdleTimeout()) >= 0) return;
      sleep(properties.progressivePollInterval());
    }
  }

  public ReadySelection openReady(MediaJob job, String rangeHeader) throws IOException {
    MediaStoredFile stored = storage.readyFile(job);
    long total = stored.length();
    if (rangeHeader == null) return new ReadySelection(stored, 0, total, total, false);
    try {
      List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
      if (ranges.size() != 1 || total == 0) throw invalidRange(total, stored);
      long start = ranges.getFirst().getRangeStart(total);
      long end = ranges.getFirst().getRangeEnd(total);
      if (start < 0 || end < start || end >= total) throw invalidRange(total, stored);
      return new ReadySelection(stored, start, end - start + 1, total, true);
    } catch (IllegalArgumentException exception) {
      throw invalidRange(total, stored);
    }
  }

  private Path outputPath(MediaJob job, MediaJobStatus effectiveStatus) throws IOException {
    if (effectiveStatus == MediaJobStatus.READY && storage.readyExists(job)) {
      return storage.readyPath(job);
    }
    return storage.partialPath(job);
  }

  private long ordinarySize(Path path) throws IOException {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return 0;
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
      throw new IOException("Media output is unavailable");
    }
    return Files.size(path);
  }

  private void sleep(Duration duration) throws IOException {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("Media streaming was interrupted", exception);
    }
  }

  private SharedFolderRangeNotSatisfiableException invalidRange(
      long total, MediaStoredFile stored) {
    stored.close();
    return new SharedFolderRangeNotSatisfiableException(total);
  }

  /** Ready disk region and its eviction lease. */
  public record ReadySelection(
      MediaStoredFile stored, long start, long length, long totalLength, boolean partial) {
    public org.springframework.core.io.Resource resource() { return stored.resource(); }
    public void close() { stored.close(); }
  }
}
