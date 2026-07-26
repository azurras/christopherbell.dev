package dev.christopherbell.sharedfolder.radio;

import dev.christopherbell.sharedfolder.model.SharedFolderRadioDurationRequest;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Fixed-key durable state for the one shared-folder radio station. */
@Document("shared_folder_radio")
public record SharedFolderRadioDocument(
    @Id String id,
    long stationSequence,
    String path,
    Instant startedAt,
    Double durationSeconds) {
  public static final String ID = "shared-folder-radio";

  /** Rejects malformed persisted state before station transitions rely on it. */
  public SharedFolderRadioDocument {
    if (!ID.equals(id) || stationSequence < 1 || path == null || path.isBlank()
        || startedAt == null) {
      throw new IllegalArgumentException("Shared-folder radio document is invalid");
    }
    if (durationSeconds != null
        && !SharedFolderRadioDurationRequest.isValidDuration(durationSeconds)) {
      throw new IllegalArgumentException("Shared-folder radio duration is invalid");
    }
  }
}
