package dev.christopherbell.post.feed;

import dev.christopherbell.libs.pagination.StableCursor;
import java.util.Collection;
import java.util.Optional;

/** Persistence-neutral stable feed query boundary. */
public interface PostFeedQueryPort {
  PostFeedSlice global(Optional<StableCursor> cursor, int requestedSize);

  PostFeedSlice global(
      Optional<StableCursor> cursor, int requestedSize, PostFeedVisibility visibility);

  PostFeedSlice account(String accountId, Optional<StableCursor> cursor, int requestedSize);

  PostFeedSlice account(
      String accountId,
      Optional<StableCursor> cursor,
      int requestedSize,
      PostFeedVisibility visibility);

  PostFeedSlice accounts(
      Collection<String> accountIds, Optional<StableCursor> cursor, int requestedSize);

  PostFeedSlice following(
      String followerId,
      Optional<StableCursor> cursor,
      int requestedSize,
      PostFeedVisibility visibility);
}
