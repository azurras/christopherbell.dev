package dev.christopherbell.music.metadata;

import java.nio.file.Path;

/** Rewrites tags into a new private file without mutating the source. */
@FunctionalInterface
public interface MusicTagProcess {
  void rewrite(Path source, Path destination, MusicMetadataUpdate update, Path artwork);
}
