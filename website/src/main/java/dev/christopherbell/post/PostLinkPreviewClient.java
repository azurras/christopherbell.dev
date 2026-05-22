package dev.christopherbell.post;

import dev.christopherbell.post.model.PostLinkPreview;
import java.util.Optional;

/**
 * Fetches metadata for one external URL so posts can store link previews once.
 */
public interface PostLinkPreviewClient {
  /**
   * Resolves one public web URL into display metadata when the page exposes it.
   *
   * @param url absolute HTTP or HTTPS URL
   * @return preview metadata when the URL can be fetched and summarized
   */
  Optional<PostLinkPreview> fetch(String url);
}
