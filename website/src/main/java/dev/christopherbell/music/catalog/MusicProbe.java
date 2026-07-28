package dev.christopherbell.music.catalog;

import java.nio.file.Path;

/** Extracts validated metadata from one trusted Music-root file path. */
@FunctionalInterface
public interface MusicProbe {
  MusicProbeResult probe(Path source);
}
