package dev.christopherbell.sharedfolder.media;

import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** Owned media job metadata; browser responses never serialize this persistence model. */
@Data
@NoArgsConstructor
@Document("shared_folder_media_jobs")
public class MediaJob {
  @Id private String id;
  @Version private Long version;
  @Indexed private String ownerId;
  private String sourcePath;
  private long sourceSize;
  private Instant sourceModifiedAt;
  private MediaOutputProfile profile;
  private int profileVersion;
  @Indexed private String cacheKey;
  @Indexed private MediaJobStatus status;
  private String failureCategory;
  private long outputBytes;
  private Instant deadline;
  private Instant createdAt;
  @Indexed private Instant updatedAt;
  private Instant lastAccessedAt;
}
