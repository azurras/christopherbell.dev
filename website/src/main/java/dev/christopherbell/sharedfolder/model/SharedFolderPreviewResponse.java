package dev.christopherbell.sharedfolder.model;

/** JSON payload for a bounded, text-only shared-folder preview. */
public record SharedFolderPreviewResponse(String text, boolean truncated) {}
