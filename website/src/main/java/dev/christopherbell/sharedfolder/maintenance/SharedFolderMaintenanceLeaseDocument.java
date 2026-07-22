package dev.christopherbell.sharedfolder.maintenance;

import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** The single fixed-key Mongo document coordinating shared-folder maintenance processes. */
@Data
@NoArgsConstructor
@Document("shared_folder_maintenance_leases")
final class SharedFolderMaintenanceLeaseDocument {
  static final String ID = "shared-folder-maintenance";

  @Id private String id;
  private String ownerToken;
  private Instant acquiredAt;
  private Instant expiresAt;
}
