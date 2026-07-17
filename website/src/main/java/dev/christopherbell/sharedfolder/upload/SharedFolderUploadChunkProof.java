package dev.christopherbell.sharedfolder.upload;

/** Public-safe proof of one durably committed upload chunk. */
public record SharedFolderUploadChunkProof(long offset, long length, String sha256) {}
