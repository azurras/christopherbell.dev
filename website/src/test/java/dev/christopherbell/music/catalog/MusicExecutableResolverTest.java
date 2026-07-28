package dev.christopherbell.music.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class MusicExecutableResolverTest {
  @TempDir Path tempDir;

  @Test
  void enabledMusicResolvesTheHashVerifiedPinnedToolSet() throws Exception {
    Path toolRoot = tempDir.toRealPath();
    Path version = toolRoot.resolve("versions/ffmpeg-8.0.1");
    Files.createDirectories(version.resolve("bin"));
    Path ffmpeg = Files.writeString(version.resolve("bin/ffmpeg.exe"), "ffmpeg");
    Path ffprobe = Files.writeString(version.resolve("bin/ffprobe.exe"), "ffprobe");
    Files.writeString(toolRoot.resolve("active-media-tools.json"), """
        {"schemaVersion":1,"packageVersion":"8.0.1","packageSha256":"%s",
         "versionDirectory":"ffmpeg-8.0.1","ffmpegSha256":"%s","ffprobeSha256":"%s"}
        """.formatted("a".repeat(64), sha256(ffmpeg), sha256(ffprobe)));

    var resolver = new MusicExecutableResolver(
        true, "ffmpeg", "ffprobe", new MusicMediaToolProperties(toolRoot), new ObjectMapper());

    assertThat(resolver.resolve("ffmpeg")).isEqualTo(ffmpeg.toAbsolutePath().normalize().toString());
    assertThat(resolver.resolve("ffprobe")).isEqualTo(ffprobe.toAbsolutePath().normalize().toString());
  }

  @Test
  void enabledMusicRejectsATamperedPinnedExecutable() throws Exception {
    Path toolRoot = tempDir.toRealPath();
    Path version = toolRoot.resolve("versions/ffmpeg-8.0.1");
    Files.createDirectories(version);
    Path ffmpeg = Files.writeString(version.resolve("ffmpeg.exe"), "ffmpeg");
    Path ffprobe = Files.writeString(version.resolve("ffprobe.exe"), "ffprobe");
    Files.writeString(toolRoot.resolve("active-media-tools.json"), """
        {"schemaVersion":1,"packageVersion":"8.0.1","packageSha256":"%s",
         "versionDirectory":"ffmpeg-8.0.1","ffmpegSha256":"%s","ffprobeSha256":"%s"}
        """.formatted("a".repeat(64), sha256(ffmpeg), sha256(ffprobe)));
    Files.writeString(ffmpeg, "tampered");

    assertThatThrownBy(() -> new MusicExecutableResolver(
        true, "ffmpeg", "ffprobe", new MusicMediaToolProperties(toolRoot), new ObjectMapper()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("hash");
  }

  @Test
  void disabledMusicDoesNotRequireInstalledMediaTools() {
    var resolver = new MusicExecutableResolver(
        false, "ffmpeg", "ffprobe", new MusicMediaToolProperties(tempDir), new ObjectMapper());

    assertThat(resolver.resolve("ffmpeg")).isEqualTo("ffmpeg");
    assertThat(resolver.resolve("ffprobe")).isEqualTo("ffprobe");
  }

  private String sha256(Path path) throws Exception {
    return HexFormat.of().withUpperCase().formatHex(
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
  }
}
