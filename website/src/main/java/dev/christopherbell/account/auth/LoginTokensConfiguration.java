package dev.christopherbell.account.auth;

import dev.christopherbell.account.api.LoginTokens;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Builds the application's single {@link LoginTokens} from {@code app.jwt.secret}. */
@Configuration(proxyBeanMethods = false)
public class LoginTokensConfiguration {

  @Bean
  public LoginTokens loginTokens(
      @Value("${app.jwt.secret:}") String configuredSecret,
      Environment environment,
      Clock applicationClock) {
    boolean isProductionProfile = environment.acceptsProfiles(Profiles.of("prod"));
    return LoginTokens.fromConfiguration(
        configuredSecret, isProductionProfile, System.getenv(), applicationClock);
  }
}
