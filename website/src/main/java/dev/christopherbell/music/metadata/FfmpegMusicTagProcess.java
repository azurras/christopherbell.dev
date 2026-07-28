package dev.christopherbell.music.metadata;

import dev.christopherbell.music.catalog.MusicProcessRunner;
import dev.christopherbell.music.catalog.MusicProbeException;
import dev.christopherbell.music.catalog.MusicProperties;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Fixed-argv FFmpeg tag writer that always stream-copies audio. */
public final class FfmpegMusicTagProcess implements MusicTagProcess {
  private final MusicProperties music;
  private final MusicProcessRunner runner;

  public FfmpegMusicTagProcess(MusicProperties music, MusicProcessRunner runner) {
    this.music = music;
    this.runner = runner;
  }

  @Override
  public void rewrite(
      Path source,
      Path destination,
      MusicMetadataUpdate update,
      Path artwork) {
    var command = new ArrayList<>(List.of(
        music.ffmpegCommand(), "-nostdin", "-v", "error", "-y", "-i", source.toString()));
    if (artwork != null) command.addAll(List.of("-i", artwork.toString()));
    if (artwork != null) {
      command.addAll(List.of("-map", "0:a", "-map", "1:v:0", "-c:a", "copy", "-c:v", "copy",
          "-disposition:v:0", "attached_pic"));
    } else if (update.removeArtwork()) {
      command.addAll(List.of("-map", "0:a", "-c:a", "copy"));
    } else {
      command.addAll(List.of("-map", "0", "-c", "copy"));
    }
    command.addAll(List.of("-map_metadata", "0"));
    metadata(command, "title", update.title());
    metadata(command, "artist", update.artist());
    metadata(command, "album_artist", update.albumArtist());
    metadata(command, "album", update.album());
    metadata(command, "track", number(update.trackNumber()));
    metadata(command, "disc", number(update.discNumber()));
    metadata(command, "genre", update.genre());
    metadata(command, "date", number(update.year()));
    command.add(destination.toString());

    var result = runner.run(List.copyOf(command));
    if (result.timedOut() || result.outputTruncated() || result.exitCode() != 0
        || result.stderr() == null || !result.stderr().isBlank()) {
      throw new MusicProbeException("FFmpeg could not create a validated metadata rewrite.");
    }
  }

  private void metadata(List<String> command, String name, String value) {
    command.add("-metadata");
    command.add(name + '=' + (value == null ? "" : value));
  }

  private String number(Integer value) {
    return value == null ? null : value.toString();
  }
}
