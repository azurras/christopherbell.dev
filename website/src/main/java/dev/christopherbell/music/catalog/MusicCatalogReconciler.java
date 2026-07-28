package dev.christopherbell.music.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.scheduling.annotation.Scheduled;

/** Reconciles disk state into Mongo while bounding expensive media probes per pass. */
public class MusicCatalogReconciler {
  private static final Set<String> AUDIO_EXTENSIONS = Set.of(
      "aac", "alac", "flac", "m4a", "mka", "mp3", "ogg", "opus", "wav", "wma");
  private static final Duration FAILED_PROBE_RETRY = Duration.ofHours(1);
  private final MusicProperties properties;
  private final MusicTrackRepository tracks;
  private final MusicProbe probe;
  private final MusicArtworkService artwork;
  private final Clock clock;

  public MusicCatalogReconciler(
      MusicProperties properties,
      MusicTrackRepository tracks,
      MusicProbe probe,
      MusicArtworkService artwork,
      Clock clock) {
    this.properties = properties;
    this.tracks = tracks;
    this.probe = probe;
    this.artwork = artwork;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${app.music.scan-interval:5m}")
  public void scheduledReconcile() {
    if (properties.enabled()) reconcile();
  }

  public MusicReconcileResult reconcile() {
    if (!properties.enabled()) return new MusicReconcileResult(0, 0, 0, 0, 0, 0);
    Path root = safeRoot();
    var candidates = discover(root);
    Set<String> presentPaths = new HashSet<>();
    int probed = 0;
    int updated = 0;
    int unchanged = 0;
    int failed = 0;
    for (Path source : candidates) {
      String relative = relative(root, source);
      presentPaths.add(relative);
      MusicFileRevision revision;
      try {
        revision = MusicFileRevision.observe(source);
      } catch (IOException failure) {
        failed++;
        continue;
      }
      var existing = tracks.findByPath(relative).orElse(null);
      boolean changed = existing == null
          || existing.indexStatus() != MusicIndexStatus.READY
          || existing.missingSince() != null
          || !revision.token().equals(existing.observedToken());
      if (!changed || retryDeferred(existing, revision)) {
        unchanged++;
        continue;
      }
      if (probed >= properties.scanBatchSize()) continue;
      probed++;
      try {
        var metadata = probe.probe(source.toAbsolutePath().normalize());
        var artworkRevision = metadata.hasArtwork()
            ? artwork.extract(source, relative, revision).orElse(null)
            : null;
        tracks.save(MusicTrack.indexed(
            existing, relative, revision.token(), metadata, artworkRevision, clock.instant()));
        updated++;
      } catch (RuntimeException failure) {
        tracks.save(MusicTrack.probeFailed(
            existing, relative, revision.token(), failureCategory(failure), clock.instant()));
        failed++;
      }
    }

    int missing = 0;
    for (MusicTrack track : tracks.findAllByMissingSinceIsNull()) {
      if (!presentPaths.contains(track.path())) {
        tracks.save(track.markMissing(clock.instant()));
        missing++;
      }
    }
    return new MusicReconcileResult(
        candidates.size(), probed, updated, unchanged, missing, failed);
  }

  private boolean retryDeferred(MusicTrack existing, MusicFileRevision revision) {
    return existing != null
        && existing.indexStatus() == MusicIndexStatus.PROBE_FAILED
        && revision.token().equals(existing.pendingObservedToken())
        && existing.lastProbeAttemptAt() != null
        && clock.instant().isBefore(existing.lastProbeAttemptAt().plus(FAILED_PROBE_RETRY));
  }

  private Path safeRoot() {
    Path root = properties.root().toAbsolutePath().normalize();
    if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException("Music root is unavailable or unsafe.");
    }
    return root;
  }

  private java.util.List<Path> discover(Path root) {
    try (var paths = Files.walk(root, 64)) {
      return paths
          .filter(path -> !path.equals(root))
          .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
          .filter(path -> !Files.isSymbolicLink(path))
          .filter(this::supported)
          .sorted((left, right) -> relative(root, left).compareToIgnoreCase(relative(root, right)))
          .toList();
    } catch (IOException | SecurityException failure) {
      throw new IllegalStateException("Music root cannot be scanned.", failure);
    }
  }

  private boolean supported(Path path) {
    String name = path.getFileName().toString();
    int dot = name.lastIndexOf('.');
    return dot >= 0 && AUDIO_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
  }

  private String relative(Path root, Path source) {
    String relative = root.relativize(source.toAbsolutePath().normalize()).toString()
        .replace('\\', '/');
    if (relative.isBlank() || relative.startsWith("../") || relative.contains("/../")) {
      throw new IllegalStateException("Music scan produced an unsafe relative path.");
    }
    return relative;
  }

  private String failureCategory(RuntimeException failure) {
    String name = failure.getClass().getSimpleName();
    return name.length() <= 80 ? name : "ProbeFailure";
  }
}
