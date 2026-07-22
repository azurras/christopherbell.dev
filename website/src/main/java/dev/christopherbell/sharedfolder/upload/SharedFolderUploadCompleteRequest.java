package dev.christopherbell.sharedfolder.upload;

/** Explicitly opts into replacement only when finalizing an owned upload. */
public record SharedFolderUploadCompleteRequest(boolean replace) {}
