package dev.christopherbell.sharedfolder.media;

import dev.christopherbell.configuration.SharedFolderMediaProperties;
import dev.christopherbell.sharedfolder.service.SharedFolderRangeNotSatisfiableException;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpRange;
import org.springframework.stereotype.Component;

/** Sequentially streams held-handle media regions with bounded growing-file polling. */
@Component
public final class ProgressiveMediaStreamer {
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
    long maximum = properties.maxOutput().toBytes();
    while (!Thread.currentThread().isInterrupted()) {
      MediaJob current = jobs.findById(original.getId()).orElse(original);
      if (media != null) current = media.refreshWorkerState(current);
      MediaJob observed = current;
      var workerStatus = storage.readStatus(observed, maximum);
      MediaJobStatus effectiveStatus = workerStatus.map(MediaStorage.MediaWorkerStatus::status)
          .filter(status -> status != MediaJobStatus.READY
              || storage.readyMatches(observed, workerStatus.get().outputBytes(), maximum))
          .orElse(observed.getStatus());
      boolean ready = effectiveStatus == MediaJobStatus.READY;
      long size = ready ? storage.readyLength(observed, maximum)
          : storage.partialLength(observed, maximum);
      if (size < 0) throw new IOException("Media output is unavailable");
      if (!started && size >= properties.initialBuffer().toBytes()) started = true;
      if (!started && effectiveStatus.terminal()) started = size > 0;
      if (started && size > position) {
        long copied = storage.copy(observed, ready, position, size - position, output, maximum);
        position += copied;
        output.flush();
        if (copied > 0) idleSince = System.nanoTime();
      }
      if (effectiveStatus.terminal() && position >= size) return;
      if (Duration.ofNanos(System.nanoTime() - idleSince)
          .compareTo(properties.progressiveIdleTimeout()) >= 0) return;
      sleep(properties.progressivePollInterval());
    }
  }

  public ReadySelection openReady(MediaJob job, String rangeHeader) throws IOException {
    long total = storage.readyLength(job, properties.maxOutput().toBytes());
    if (total < 0) throw new IOException("Media output is unavailable");
    if (rangeHeader == null) return new ReadySelection(job, 0, total, total, false);
    try {
      List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
      if (ranges.size() != 1 || total == 0) throw invalidRange(total);
      long start = ranges.getFirst().getRangeStart(total);
      long end = ranges.getFirst().getRangeEnd(total);
      if (start < 0 || end < start || end >= total) throw invalidRange(total);
      return new ReadySelection(job, start, end - start + 1, total, true);
    } catch (IllegalArgumentException exception) {
      throw invalidRange(total);
    }
  }

  public void copyReady(ReadySelection selection, OutputStream output) throws IOException {
    long copied = storage.copy(
        selection.job(), true, selection.start(), selection.length(), output,
        properties.maxOutput().toBytes());
    if (copied != selection.length()) throw new IOException("Media output changed during streaming");
    output.flush();
  }

  private void sleep(Duration duration) throws IOException {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("Media streaming was interrupted", exception);
    }
  }

  private SharedFolderRangeNotSatisfiableException invalidRange(long total) {
    return new SharedFolderRangeNotSatisfiableException(total);
  }

  /** Completed held-handle range selection. */
  public record ReadySelection(
      MediaJob job, long start, long length, long totalLength, boolean partial) {}
}
