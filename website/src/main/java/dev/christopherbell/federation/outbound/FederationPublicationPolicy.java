package dev.christopherbell.federation.outbound;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.federation.configuration.FederationProperties;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Decides once, at post creation, whether outbound federation may ever publish that post. */
@Component
public final class FederationPublicationPolicy {
  private final FederationProperties properties;

  public FederationPublicationPolicy(FederationProperties properties) {
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  public boolean eligibleAtCreation(Account account) {
    return account != null
        && properties.outboundEnabled()
        && account.getStatus() == AccountStatus.ACTIVE
        && account.isFederationEnabled()
        && account.getFederationIdentity() != null;
  }
}
