package dev.christopherbell.account.follow;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;

/** One directional follow relationship between two accounts. */
@AllArgsConstructor
@Builder
@Data
@CompoundIndex(
    name = "account_follow_follower_target_unique",
    def = "{'followerAccountId': 1, 'followedAccountId': 1}",
    unique = true)
@NoArgsConstructor
public class AccountFollow {
  public static final String COLLECTION = "account_follows";

  @Id private String id;
  private String followerAccountId;
  private String followedAccountId;
  private Instant createdOn;
}
