package dev.christopherbell.sharedfolder.upload;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/** Mongo-backed owned upload metadata; only a random private staging key, never a local path, is kept. */
@Data
@NoArgsConstructor
@Document("shared_folder_upload_sessions")
@CompoundIndexes({
    @CompoundIndex(name = "upload_owner_state", def = "{'ownerId': 1, 'state': 1}"),
    @CompoundIndex(
        name = "upload_maintenance_due",
        def = "{'state': 1, 'maintenanceRetryAt': 1, 'expiresAt': 1, '_id': 1}")
})
public class SharedFolderUploadSession {
  @Id private String id;
  @Version private Long version;
  @Indexed private String ownerId;
  private String parentPath;
  private String name;
  private long expectedBytes;
  private String expectedSha256;
  private String targetObservedToken;
  private long nextOffset;
  private Map<String, String> chunkDigests = new HashMap<>();
  private Map<String, Long> chunkLengths = new HashMap<>();
  private String stagingKey;
  private String appendLeaseToken;
  private Instant appendLeaseExpiresAt;
  private Long appendOffset;
  private Long appendLength;
  private String appendDigest;
  private String appendChunkKey;
  private String finalizingIdentity;
  private Boolean finalizingReplace;
  private String finalizingTargetIdentity;
  private String finalizingQuarantineKey;
  private SharedFolderUploadFinalizationState finalizationState;
  private String finalizationLeaseToken;
  private Instant finalizationLeaseExpiresAt;
  @Indexed private Instant expiresAt;
  private Instant maintenanceRetryAt;
  private int maintenanceAttempts;
  private SharedFolderUploadState state;
  private Instant createdAt;
  private Instant updatedAt;
}
