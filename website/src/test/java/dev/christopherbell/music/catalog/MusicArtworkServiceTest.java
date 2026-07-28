package dev.christopherbell.music.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MusicArtworkServiceTest {
  @TempDir Path tempDir;

  @Test
  void extractsValidatedArtworkToRevisionAddressedPrivateCache() throws Exception {
    Path source = Files.writeString(tempDir.resolve("song.flac"), "audio");
    var runner = new ImageWritingRunner(false);
    var service = new MusicArtworkService(properties(5_242_880), runner);

    String revision = service.extract(
        source, "Artist/song.flac", MusicFileRevision.observe(source)).orElseThrow();

    assertThat(revision).matches("[a-f0-9]{64}");
    assertThat(service.resolve(revision)).isPresent();
    assertThat(runner.command()).contains("-nostdin", "-map", "0:v:0", "-frames:v", "1");
    assertThat(service.resolve("../../outside")).isEmpty();
  }

  @Test
  void rejectsArtworkThatExceedsTheConfiguredByteLimit() throws Exception {
    Path source = Files.writeString(tempDir.resolve("song.m4a"), "audio");
    var service = new MusicArtworkService(properties(1024), new ImageWritingRunner(true));

    assertThat(service.extract(
        source, "song.m4a", MusicFileRevision.observe(source))).isEmpty();
  }

  private MusicProperties properties(int artworkMaxBytes) {
    return new MusicProperties(
        tempDir,
        tempDir.resolve("private-artwork"),
        "ffprobe",
        "ffmpeg",
        100,
        Duration.ofMinutes(5),
        Duration.ofSeconds(20),
        1_048_576,
        artworkMaxBytes,
        2048,
        true);
  }

  private static final class ImageWritingRunner implements MusicProcessRunner {
    private final boolean oversized;
    private List<String> command;

    private ImageWritingRunner(boolean oversized) {
      this.oversized = oversized;
    }

    @Override
    public MusicProcessResult run(List<String> command) {
      this.command = List.copyOf(command);
      Path output = Path.of(command.getLast());
      try {
        if (oversized) {
          Files.write(output, new byte[2048]);
        } else {
          ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "jpg", output.toFile());
        }
      } catch (java.io.IOException failure) {
        throw new IllegalStateException(failure);
      }
      return new MusicProcessResult("", "", 0, false, false);
    }

    private List<String> command() {
      return command;
    }
  }
}
