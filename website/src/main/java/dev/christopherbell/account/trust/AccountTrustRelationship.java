package dev.christopherbell.account.trust;

import dev.christopherbell.account.trust.model.AccountTrustType;
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

/** Stores one mute or block relationship from an owner account to a target account. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("account_trust_relationships")
@CompoundIndex(
    name = "owner_target_type_unique",
    def = "{'ownerAccountId': 1, 'targetAccountId': 1, 'type': 1}",
    unique = true)
public class AccountTrustRelationship {
  @Id private String id;
  @Indexed private String ownerAccountId;
  @Indexed private String targetAccountId;
  private AccountTrustType type;
  @CreatedDate private Instant createdOn;
}
