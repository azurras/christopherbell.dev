package dev.christopherbell.post.preview;

/** Safe categorized link-preview fetch failure suitable for short-lived negative caching. */
public final class LinkPreviewFetchException extends RuntimeException {
  private final String category;

  public LinkPreviewFetchException(String category) {
    super("Link preview fetch failed: " + category);
    this.category = category;
  }

  public LinkPreviewFetchException(String category, Throwable cause) {
    super("Link preview fetch failed: " + category, cause);
    this.category = category;
  }

  public String category() {
    return category;
  }
}
