package dev.christopherbell.configuration.mongo.lease;

import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@NoArgsConstructor
@Document(MongoLeaseService.COLLECTION)
class MongoLeaseDocument {
  @Id private String id;
  private String ownerToken;
  private Instant acquiredAt;
  private Instant expiresAt;
}
