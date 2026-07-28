package dev.christopherbell.music.catalog;

import java.util.List;

/** Executes a fixed argv vector without involving a command shell. */
@FunctionalInterface
public interface MusicProcessRunner {
  MusicProcessResult run(List<String> command);
}
