package dev.christopherbell.sharedfolder.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.sharedfolder.fs.PortableSharedFolderPrivateBoundary;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.ObjectMapper;

class MediaStorageTest {
  @TempDir Path temp;
  private Path system;
  private MediaStorage storage;

  @BeforeEach
  void setUp() throws Exception {
    Path shared = Files.createDirectory(temp.resolve("shared"));
    system = Files.createDirectory(temp.resolve("system"));
    storage = storage(shared, new PortableSharedFolderPrivateBoundary(system));
  }

  @Test
  void productionConstructorIsSelectedByTheRealSpringContext() {
    SharedFolderProperties disabled = new SharedFolderProperties(
        temp.resolve("disabled-shared"), temp.resolve("disabled-system"),
        DataSize.ofGigabytes(1), DataSize.ofMegabytes(1),
        DataSize.ofBytes(1), DataSize.ofBytes(10), Duration.ofDays(1),
        Duration.ofDays(1), false);
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(SharedFolderProperties.class, () -> disabled);
      context.register(MediaStorage.class);

      context.refresh();

      assertThat(context.getBean(MediaStorage.class)).isNotNull();
    }
  }

  @Test
  void heldHandleCopyEnforcesActualOutputCapAndReleasesReaderLease() throws Exception {
    MediaJob ready = job("ready", "a".repeat(64));
    storage.writeReadyForTest(ready, "12345678".getBytes());

    assertThatThrownBy(() -> storage.readyLength(ready, 7)).isInstanceOf(IOException.class);
    assertThat(storage.readyLength(job("missing", "c".repeat(64)), 7)).isEmpty();
    assertThatThrownBy(() -> storage.copy(
        ready, true, 0, 8, new ByteArrayOutputStream(), 7)).isInstanceOf(IOException.class);
    assertThat(storage.isBeingRead(ready.getCacheKey())).isFalse();

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    assertThat(storage.copy(ready, true, 2, 4, output, 8)).isEqualTo(4);
    assertThat(output.toString()).isEqualTo("3456");
    assertThat(storage.isBeingRead(ready.getCacheKey())).isFalse();
  }

  @Test
  void leafSubstitutionBetweenValidationAndOpenFailsClosed() throws Exception {
    Path shared = temp.resolve("shared");
    Files.writeString(temp.resolve("outside.bin"), "outside-secret");
    SubstitutingBoundary boundary = new SubstitutingBoundary(system, temp.resolve("outside.bin"));
    MediaStorage guarded = storage(shared, boundary);
    MediaJob ready = job("ready", "b".repeat(64));
    guarded.writeReadyForTest(ready, "safe".getBytes());
    boundary.arm();

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    assertThatThrownBy(() -> guarded.copy(ready, true, 0, 4, output, 100))
        .isInstanceOf(IOException.class);
    assertThat(output.toString()).isIn("", "safe");
    assertThat(output.toString()).doesNotContain("outside-secret");
  }

  @Test
  void workerStatusDistinguishesAbsenceProtocolFailureAndOutputLimit() throws Exception {
    MediaJob job = job("job-1", "d".repeat(64));
    assertThat(storage.readStatus(job, 100)).isEmpty();

    Files.writeString(system.resolve(MediaStorage.STATUS).resolve("job-1.json"), "not-json");
    assertThatThrownBy(() -> storage.readStatus(job, 100))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("protocol");

    Files.delete(system.resolve(MediaStorage.STATUS).resolve("job-1.json"));
    storage.writeStatusForTest(job, MediaJobStatus.BUFFERING, 101, null);
    assertThatThrownBy(() -> storage.readStatus(job, 100))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("limit");
  }

  private MediaStorage storage(Path shared, PortableSharedFolderPrivateBoundary boundary)
      throws Exception {
    SharedFolderProperties properties = new SharedFolderProperties(
        shared, system, DataSize.ofGigabytes(1), DataSize.ofMegabytes(1),
        DataSize.ofBytes(1), DataSize.ofBytes(10), Duration.ofDays(1),
        Duration.ofDays(1), true);
    MediaStorage result = new MediaStorage(system, true, new ObjectMapper(), boundary);
    result.initialize();
    return result;
  }

  private MediaJob job(String id, String cacheKey) {
    MediaJob job = new MediaJob();
    job.setId(id);
    job.setCacheKey(cacheKey);
    job.setProfile(MediaOutputProfile.VIDEO_MP4);
    return job;
  }

  private static final class SubstitutingBoundary extends PortableSharedFolderPrivateBoundary {
    private final Path outside;
    private boolean armed;

    private SubstitutingBoundary(Path root, Path outside) {
      super(root);
      this.outside = outside;
    }

    void arm() { armed = true; }

    @Override
    protected void beforePrivateFileChannelOpen(Path path, FileAccess access) throws IOException {
      if (!armed || access != FileAccess.READ || !path.getFileName().toString().endsWith(".mp4")) {
        return;
      }
      armed = false;
      Files.delete(path);
      Files.createLink(path, outside);
    }
  }
}
