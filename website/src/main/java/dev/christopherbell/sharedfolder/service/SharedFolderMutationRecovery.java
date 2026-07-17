package dev.christopherbell.sharedfolder.service;

import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** Owner-scoped bounded recovery journal for one conditional shared-folder replacement. */
@Data
@NoArgsConstructor
@Document("shared_folder_mutation_recoveries")
public class SharedFolderMutationRecovery {
  @Id private String id;
  @Version private Long version;
  @Indexed private String ownerId;
  private String sourcePath;
  private String destinationParentPath;
  private String name;
  private String sourceIdentity;
  private String targetIdentity;
  private String quarantineKey;
  private boolean nativeMode;
  private SharedFolderMutationRecoveryState state;
  private Instant createdAt;
  @Indexed private Instant updatedAt;

  /** Returns a detached value for deterministic repository and recovery tests. */
  public SharedFolderMutationRecovery copy() {
    SharedFolderMutationRecovery copy = new SharedFolderMutationRecovery();
    copy.setId(id);
    copy.setVersion(version);
    copy.setOwnerId(ownerId);
    copy.setSourcePath(sourcePath);
    copy.setDestinationParentPath(destinationParentPath);
    copy.setName(name);
    copy.setSourceIdentity(sourceIdentity);
    copy.setTargetIdentity(targetIdentity);
    copy.setQuarantineKey(quarantineKey);
    copy.setNativeMode(nativeMode);
    copy.setState(state);
    copy.setCreatedAt(createdAt);
    copy.setUpdatedAt(updatedAt);
    return copy;
  }
}
