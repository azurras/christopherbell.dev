package dev.christopherbell.music.web;

/** Public-safe Music entry state. */
public record MusicAccessStatus(
    boolean authenticated,
    boolean allowed,
    boolean canManage,
    String reason) {
}
