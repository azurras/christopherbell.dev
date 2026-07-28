package dev.christopherbell.music.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.music.catalog.MusicProcessResult;
import dev.christopherbell.music.catalog.MusicProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FfmpegMusicTagProcessTest {

  @Test
  void rewriteUsesFixedArgumentsAndStreamCopiesAudioWithReplacementArtwork() {
    var command = new AtomicReference<List<String>>();
    var process = new FfmpegMusicTagProcess(properties(), value -> {
      command.set(value);
      return new MusicProcessResult("", "", 0, false, false);
    });
    var update = new MusicMetadataUpdate(
        "a".repeat(64), "Title", "Artist", "Album Artist", "Album", 2, 1,
        "Genre", 2026, "data:image/jpeg;base64,unused", false);

    process.rewrite(Path.of("C:/trusted/source.mp3"), Path.of("C:/private/stage.mp3"),
        update, Path.of("C:/private/artwork"));

    assertThat(command.get()).containsSubsequence(
        "ffmpeg", "-nostdin", "-v", "error", "-y", "-i", "C:\\trusted\\source.mp3",
        "-i", "C:\\private\\artwork", "-map", "0:a", "-map", "1:v:0",
        "-c:a", "copy", "-c:v", "copy", "-disposition:v:0", "attached_pic");
    assertThat(command.get()).doesNotContain("cmd", "/c", "powershell", "aac");
    assertThat(command.get().get(command.get().size() - 1)).isEqualTo("C:\\private\\stage.mp3");
  }

  @Test
  void rewriteRejectsTimeoutWithoutTreatingTheStageAsUsable() {
    var process = new FfmpegMusicTagProcess(
        properties(), ignored -> new MusicProcessResult("", "", -1, true, false));
    var update = new MusicMetadataUpdate(
        "a".repeat(64), "Title", null, null, null, null, null, null, null, null, false);

    assertThatThrownBy(() -> process.rewrite(
        Path.of("C:/trusted/source.flac"), Path.of("C:/private/stage.flac"), update, null))
        .hasMessageContaining("validated metadata rewrite");
  }

  private MusicProperties properties() {
    return new MusicProperties(
        Path.of("C:/music"), Path.of("C:/artwork"), "ffprobe", "ffmpeg", 100,
        Duration.ofMinutes(5), Duration.ofSeconds(20), 1_048_576, 5_242_880, 2048, true);
  }
}
