package dev.christopherbell.libs.mongo.lease;

import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;

@Data
@NoArgsConstructor
public class MongoLeaseDocument {
  @Id private String id;
  private String ownerToken;
  private Instant acquiredAt;
  private Instant expiresAt;
}
