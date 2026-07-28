package dev.christopherbell.music.catalog;

/** Bounded external-process result with explicit timeout and truncation states. */
public record MusicProcessResult(
    String stdout,
    String stderr,
    int exitCode,
    boolean timedOut,
    boolean outputTruncated) {
}
