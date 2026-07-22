package dev.christopherbell.sharedfolder.media;

import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** Owned media job metadata; browser responses never serialize this persistence model. */
@Data
@NoArgsConstructor
@Document("shared_folder_media_jobs")
@CompoundIndex(name = "media_lru", def = "{'status': 1, 'lastAccessedAt': 1, '_id': 1}")
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
  @Indexed(unique = true, sparse = true) private String activeCacheKey;
  @Indexed private MediaJobStatus status;
  private String failureCategory;
  private long outputBytes;
  private long reservedBytes;
  private boolean descriptorPublished;
  private Instant deadline;
  private Instant createdAt;
  @Indexed private Instant updatedAt;
  private Instant lastAccessedAt;
}
