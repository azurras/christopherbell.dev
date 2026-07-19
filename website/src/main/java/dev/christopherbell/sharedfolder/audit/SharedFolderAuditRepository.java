package dev.christopherbell.sharedfolder.audit;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/** Mongo persistence for bounded shared-folder audit events. */
@Repository
public interface SharedFolderAuditRepository extends MongoRepository<SharedFolderAuditEvent, String> {}
