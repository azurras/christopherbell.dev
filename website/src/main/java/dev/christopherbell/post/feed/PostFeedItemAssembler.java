package dev.christopherbell.post.feed;

import dev.christopherbell.post.like.PostLikeStore;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.post.model.PostFeedItem;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Maps a whole post collection with batched reply and like state. */
@Component
@RequiredArgsConstructor
public class PostFeedItemAssembler {
  private final PostEngagementQueryPort engagement;
  private final PostLikeStore likes;

  public List<PostFeedItem> assemble(
      List<Post> posts,
      Map<String, String> usernames,
      String viewerId
  ) {
    if (posts == null || posts.isEmpty()) {
      return List.of();
    }
    var ids = posts.stream().map(Post::getId).toList();
    var replyCounts = engagement.replyCounts(ids);
    var likeCounts = likes.counts(ids);
    var likedPostIds = likes.likedPostIds(viewerId, ids);
    return posts.stream()
        .map(post -> PostFeedItem.builder()
            .id(post.getId())
            .accountId(post.getAccountId())
            .username(usernames.get(post.getAccountId()))
            .text(post.getText())
            .linkPreviews(post.getLinkPreviews())
            .rootId(post.getRootId())
            .parentId(post.getParentId())
            .level(post.getLevel())
            .likesCount(likeCounts.getOrDefault(post.getId(), 0))
            .liked(likedPostIds.contains(post.getId()))
            .replyCount(replyCounts.getOrDefault(post.getId(), 0))
            .createdOn(post.getCreatedOn())
            .lastUpdatedOn(post.getLastUpdatedOn())
            .editedOn(post.getEditedOn())
            .lastExtendedOn(post.getLastExtendedOn())
            .topics(post.getTopics())
            .expiresOn(post.getExpiresOn())
            .build())
        .toList();
  }

  public PostFeedItem single(Post post, String username, String viewerId) {
    return assemble(List.of(post), Map.of(post.getAccountId(), username), viewerId).get(0);
  }
}
