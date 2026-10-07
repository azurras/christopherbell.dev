package dev.christopherbell.account.api;

import java.time.Clock;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Login tokens signed with the local development secret.
 *
 * <p>Instances built from the same secret share a signing key, so a token issued by one verifies
 * with any other, including the bean that {@link TestConfigurationWithLoginTokens} registers.</p>
 */
public final class LoginTokensFixture {

  private LoginTokensFixture() {
  }

  /** Returns login tokens signed with the local development secret and the system UTC clock. */
  public static LoginTokens localDevelopmentLoginTokens() {
    return LoginTokens.fromConfiguration("", false, Map.of(), Clock.systemUTC());
  }

  /** Registers {@link #localDevelopmentLoginTokens()} for web slice tests that import security. */
  @TestConfiguration(proxyBeanMethods = false)
  public static class TestConfigurationWithLoginTokens {

    @Bean
    public LoginTokens loginTokens() {
      return localDevelopmentLoginTokens();
    }
  }
}
