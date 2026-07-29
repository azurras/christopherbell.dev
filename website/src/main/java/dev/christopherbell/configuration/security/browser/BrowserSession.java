package dev.christopherbell.configuration.security.browser;

import dev.christopherbell.account.model.Role;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** Server-side browser login whose raw credential is never persisted. */
@AllArgsConstructor
@Builder
@Data
@NoArgsConstructor
@Document("browser_sessions")
public class BrowserSession {
  @Id private String id;
  @Indexed private String accountId;
  private Role role;
  private String tokenHash;
  private String previousTokenHash;
  private Instant previousTokenExpiresOn;
  private String accountSecurityFingerprint;
  private Instant createdOn;
  private Instant lastSeenOn;
  private Instant rotatedOn;
  private Instant idleExpiresOn;
  @Indexed(expireAfter = "0s") private Instant absoluteExpiresOn;
}
