package dev.christopherbell.post.feed;

import dev.christopherbell.post.model.PostFeedItem;
import java.util.List;

/** Public stable feed page with an opaque continuation cursor. */
public record PostFeedPage(List<PostFeedItem> items, String nextCursor) {
  public PostFeedPage {
    items = List.copyOf(items);
  }
}
