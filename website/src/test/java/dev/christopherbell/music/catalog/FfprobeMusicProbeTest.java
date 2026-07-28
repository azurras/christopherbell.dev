package dev.christopherbell.music.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class FfprobeMusicProbeTest {
  @TempDir Path tempDir;

  @Test
  void parsesBoundedAudioMetadataAndUsesOnlyFixedProbeArguments() {
    var runner = new FakeRunner(result("""
        {"format":{"format_name":"mov,mp4,m4a","duration":"241.75","tags":{
          "title":"  Song\\u0001 Name  ","artist":"Artist","album_artist":"Album Artist",
          "album":"Album","track":"3/12","disc":"1/2","genre":"R&B","date":"2005-01-01"}},
         "streams":[{"codec_type":"audio","codec_name":"aac"},
                    {"codec_type":"video","disposition":{"attached_pic":1}}]}
        """));
    var probe = new FfprobeMusicProbe(properties(), runner, new ObjectMapper());
    Path source = tempDir.resolve("Artist/song.m4a").toAbsolutePath().normalize();

    var metadata = probe.probe(source);

    assertThat(metadata.title()).isEqualTo("Song Name");
    assertThat(metadata.artist()).isEqualTo("Artist");
    assertThat(metadata.albumArtist()).isEqualTo("Album Artist");
    assertThat(metadata.album()).isEqualTo("Album");
    assertThat(metadata.trackNumber()).isEqualTo(3);
    assertThat(metadata.discNumber()).isEqualTo(1);
    assertThat(metadata.genre()).isEqualTo("R&B");
    assertThat(metadata.year()).isEqualTo(2005);
    assertThat(metadata.durationSeconds()).isEqualTo(241.75);
    assertThat(metadata.audioCodec()).isEqualTo("aac");
    assertThat(metadata.container()).isEqualTo("mov");
    assertThat(metadata.hasArtwork()).isTrue();
    assertThat(runner.command()).containsExactly(
        "ffprobe", "-v", "error", "-print_format", "json", "-show_format", "-show_streams",
        source.toAbsolutePath().normalize().toString());
  }

  @Test
  void rejectsTimeoutTruncationNonzeroMalformedAndMissingAudioResults() {
    assertRejected(new MusicProcessResult("", "", -1, true, false));
    assertRejected(new MusicProcessResult("{}", "", 0, false, true));
    assertRejected(new MusicProcessResult("{}", "failure", 1, false, false));
    assertRejected(result("not-json"));
    assertRejected(result("{\"format\":{\"duration\":\"30\"},\"streams\":[]}"));
  }

  private void assertRejected(MusicProcessResult result) {
    var probe = new FfprobeMusicProbe(properties(), command -> result, new ObjectMapper());
    assertThatThrownBy(() -> probe.probe(
        tempDir.resolve("song.flac").toAbsolutePath().normalize()))
        .isInstanceOf(MusicProbeException.class);
  }

  private MusicProperties properties() {
    return new MusicProperties(
        Path.of("A:/Shared/Music"),
        Path.of("A:/Shared-System/music-artwork"),
        "ffprobe",
        "ffmpeg",
        100,
        Duration.ofMinutes(5),
        Duration.ofSeconds(20),
        1_048_576,
        5_242_880,
        2048,
        true);
  }

  private MusicProcessResult result(String stdout) {
    return new MusicProcessResult(stdout, "", 0, false, false);
  }

  private static final class FakeRunner implements MusicProcessRunner {
    private final MusicProcessResult result;
    private List<String> command;

    private FakeRunner(MusicProcessResult result) {
      this.result = result;
    }

    @Override
    public MusicProcessResult run(List<String> command) {
      this.command = List.copyOf(command);
      return result;
    }

    private List<String> command() {
      return command;
    }
  }
}
