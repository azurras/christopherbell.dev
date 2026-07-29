package dev.christopherbell.post.like;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** One account's durable like relationship to one post. */
@AllArgsConstructor
@Builder
@Data
@Document(PostLike.COLLECTION)
@CompoundIndex(
    name = "post_like_post_account_unique",
    def = "{'postId': 1, 'accountId': 1}",
    unique = true)
@NoArgsConstructor
public class PostLike {
  public static final String COLLECTION = "post_likes";

  @Id private String id;
  private String postId;
  private String accountId;
  private Instant createdOn;
}
