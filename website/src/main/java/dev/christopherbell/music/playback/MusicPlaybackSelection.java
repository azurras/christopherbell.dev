package dev.christopherbell.music.playback;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import org.springframework.http.MediaType;

/** One already-authorized, held file stream and its bounded HTTP byte selection. */
public record MusicPlaybackSelection(
    InputStream input,
    String filename,
    MediaType mediaType,
    long start,
    long length,
    long totalLength,
    boolean partial) implements AutoCloseable {

  public MusicPlaybackSelection {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(filename, "filename");
    Objects.requireNonNull(mediaType, "mediaType");
    if (start < 0 || length < 0 || totalLength < 0
        || start > totalLength || length > totalLength - start) {
      throw new IllegalArgumentException("Invalid Music byte selection.");
    }
  }

  /** Copies exactly the selected bytes and fails closed if the opened file ends early. */
  public void copyTo(OutputStream output) throws IOException {
    input.skipNBytes(start);
    byte[] buffer = new byte[64 * 1024];
    long remaining = length;
    while (remaining > 0) {
      int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
      if (read < 0) {
        throw new IOException("Music file changed during playback.");
      }
      output.write(buffer, 0, read);
      remaining -= read;
    }
    output.flush();
  }

  @Override
  public void close() throws IOException {
    input.close();
  }
}
