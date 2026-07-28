package dev.christopherbell.account.model.dto;

/** Requested Music read and write capability state for one account. */
public record MusicPermissionUpdate(Boolean read, Boolean write) {
}
