package dev.christopherbell.sharedfolder.upload;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** Mongo-backed owned upload metadata; only a random private staging key, never a local path, is kept. */
@Data
@NoArgsConstructor
@Document("shared_folder_upload_sessions")
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
  private SharedFolderUploadState state;
  private Instant createdAt;
  private Instant updatedAt;
}
