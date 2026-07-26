package dev.christopherbell.post.preview;

import dev.christopherbell.post.model.PostLinkPreview;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** One expiring success or safe failure for a normalized preview URL. */
@AllArgsConstructor
@Builder
@Data
@NoArgsConstructor
@Document(PostLinkPreviewCacheEntry.COLLECTION)
public class PostLinkPreviewCacheEntry {
  public static final String COLLECTION = "post_link_preview_cache";

  @Id private String url;
  private String status;
  private PostLinkPreview preview;
  private String failureCategory;
  private Instant completedOn;
  private Instant expiresOn;

  public static PostLinkPreviewCacheEntry success(
      String url, PostLinkPreview preview, Instant completedOn, Instant expiresOn) {
    if (preview == null) {
      throw new IllegalArgumentException("Successful link-preview cache entries require preview data.");
    }
    return PostLinkPreviewCacheEntry.builder()
        .url(url)
        .status("SUCCESS")
        .preview(preview)
        .completedOn(completedOn)
        .expiresOn(expiresOn)
        .build();
  }

  public static PostLinkPreviewCacheEntry failure(
      String url, String category, Instant completedOn, Instant expiresOn) {
    if (category == null || category.isBlank()) {
      throw new IllegalArgumentException("Failed link-preview cache entries require a safe category.");
    }
    return PostLinkPreviewCacheEntry.builder()
        .url(url)
        .status("FAILURE")
        .failureCategory(category)
        .completedOn(completedOn)
        .expiresOn(expiresOn)
        .build();
  }

  public boolean isFresh(Instant now) {
    return expiresOn != null && expiresOn.isAfter(now);
  }
}
