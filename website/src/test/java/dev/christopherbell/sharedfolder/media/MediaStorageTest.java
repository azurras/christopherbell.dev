package dev.christopherbell.sharedfolder.media;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.configuration.SharedFolderProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class MediaStorageTest {
  @TempDir Path temp;
  private MediaStorage storage;

  @BeforeEach
  void setUp() throws Exception {
    Path shared = Files.createDirectory(temp.resolve("shared"));
    Path system = Files.createDirectory(temp.resolve("system"));
    storage = new MediaStorage(new SharedFolderProperties(
        shared, system, DataSize.ofGigabytes(1), DataSize.ofMegabytes(1),
        DataSize.ofBytes(1), DataSize.ofBytes(10), Duration.ofDays(1),
        Duration.ofDays(1), true));
    storage.initialize();
  }

  @Test
  void cacheLimitEvictsLeastRecentlyUsedButProtectsActiveAndStreamedOutputs() throws Exception {
    MediaJob oldest = job("old", "a".repeat(64));
    MediaJob active = job("active", "b".repeat(64));
    storage.writeReadyForTest(oldest, "12345678".getBytes());
    storage.writeReadyForTest(active, "abcdefgh".getBytes());
    Files.setLastModifiedTime(storage.readyPath(oldest),
        FileTime.from(Instant.parse("2026-07-21T00:00:00Z")));
    Files.setLastModifiedTime(storage.readyPath(active),
        FileTime.from(Instant.parse("2026-07-21T01:00:00Z")));

    storage.evictToLimit(10, Set.of(active.getCacheKey()));

    assertThat(storage.readyExists(oldest)).isFalse();
    assertThat(storage.readyExists(active)).isTrue();

    var lease = storage.readyFile(active);
    storage.evictToLimit(1, Set.of());
    assertThat(storage.readyExists(active)).isTrue();
    lease.close();
    storage.evictToLimit(1, Set.of());
    assertThat(storage.readyExists(active)).isFalse();
  }

  private MediaJob job(String id, String cacheKey) {
    MediaJob job = new MediaJob();
    job.setId(id);
    job.setCacheKey(cacheKey);
    job.setProfile(MediaOutputProfile.VIDEO_MP4);
    return job;
  }
}
