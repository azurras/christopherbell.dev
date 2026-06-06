package dev.christopherbell.post.hide;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** Stores a hidden thread root for one account. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("hidden_post_threads")
@CompoundIndex(name = "account_root_unique", def = "{'accountId': 1, 'rootPostId': 1}", unique = true)
public class HiddenPostThread {
  @Id private String id;
  @Indexed private String accountId;
  @Indexed private String rootPostId;
  @CreatedDate private Instant createdOn;
}
