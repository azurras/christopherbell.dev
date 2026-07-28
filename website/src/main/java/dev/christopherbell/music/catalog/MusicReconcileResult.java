package dev.christopherbell.music.catalog;

/** One bounded catalog reconciliation summary. */
public record MusicReconcileResult(
    int discovered,
    int probed,
    int updated,
    int unchanged,
    int missing,
    int failed) {
}
