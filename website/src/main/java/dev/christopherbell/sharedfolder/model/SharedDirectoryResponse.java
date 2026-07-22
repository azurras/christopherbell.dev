package dev.christopherbell.sharedfolder.model;

import java.util.List;

/** Public-safe listing response containing decoded relative paths only. */
public record SharedDirectoryResponse(String path, List<SharedDirectoryEntry> entries) {}
