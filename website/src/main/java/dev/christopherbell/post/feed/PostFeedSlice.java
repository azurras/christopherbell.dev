package dev.christopherbell.post.feed;

import dev.christopherbell.post.model.Post;
import java.util.List;

/** Internal stable post page before viewer-specific mapping and visibility filters. */
public record PostFeedSlice(List<Post> posts, String nextCursor) {
  public PostFeedSlice {
    posts = List.copyOf(posts);
  }
}
