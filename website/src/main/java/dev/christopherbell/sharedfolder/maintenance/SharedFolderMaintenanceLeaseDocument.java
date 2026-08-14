package dev.christopherbell.sharedfolder.maintenance;

import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;

/** The single fixed-key Mongo document coordinating shared-folder maintenance processes. */
@Data
@NoArgsConstructor
final class SharedFolderMaintenanceLeaseDocument {
  static final String ID = "shared-folder-maintenance";

  @Id private String id;
  private String ownerToken;
  private Long fenceToken;
  private Instant acquiredAt;
  private Instant expiresAt;
}
