package dev.christopherbell.sharedfolder.upload;

import org.springframework.data.mongodb.repository.MongoRepository;

/** Repository for owned resumable-upload metadata; payload bytes remain on private disk staging. */
public interface SharedFolderUploadSessionRepository extends MongoRepository<SharedFolderUploadSession, String> {}
