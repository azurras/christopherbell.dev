package dev.christopherbell.federation.configuration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

class FederationPropertiesTest {

  @Test
  void disabledFederationDoesNotRequireASecret() {
    new FederationProperties(false, false, false, "christopherbell.dev", "1.0", null, null);
    new FederationProperties(
        false, false, false, "christopherbell.dev", "1.0", "not-base64", null);
  }

  @Test
  void inboundAndOutboundCannotRunWithoutDiscovery() {
    assertThrows(IllegalArgumentException.class,
        () -> new FederationProperties(false, true, false, "site", "1.0", null, null));
    assertThrows(IllegalArgumentException.class,
        () -> new FederationProperties(false, false, true, "site", "1.0", null, null));
  }

  @Test
  void enabledDiscoveryRequiresAValidAes256Secret() {
    assertThrows(IllegalArgumentException.class,
        () -> new FederationProperties(true, false, false, "site", "1.0", null, null));
    assertThrows(IllegalArgumentException.class,
        () -> new FederationProperties(
            true, false, false, "site", "1.0", "not-base64", null));
    assertThrows(IllegalArgumentException.class,
        () -> new FederationProperties(
            true,
            false,
            false,
            "site",
            "1.0",
            Base64.getEncoder().encodeToString(new byte[31]),
            null));
  }

  @Test
  void enabledDiscoveryReturnsOnlyACopyOfTheDecodedSecret() {
    byte[] expected = new byte[32];
    expected[0] = 42;
    var properties = new FederationProperties(
        true,
        false,
        false,
        "christopherbell.dev",
        "1.0",
        Base64.getEncoder().encodeToString(expected),
        null);

    byte[] first = properties.requiredEncryptionSecret();
    first[0] = 0;

    assertArrayEquals(expected, properties.requiredEncryptionSecret());
  }

  @Test
  void enabledOutboundRequiresADatedControlledPeerAllowList() {
    var secret = Base64.getEncoder().encodeToString(new byte[32]);

    assertThrows(IllegalArgumentException.class,
        () -> new FederationProperties(
            true, false, true, "site", "1.0", secret, outbound(null, List.of())));
    assertThrows(IllegalArgumentException.class,
        () -> new FederationProperties(
            true,
            false,
            true,
            "site",
            "1.0",
            secret,
            outbound(Instant.parse("2026-07-28T00:00:00Z"), List.of())));
  }

  @Test
  void enabledOutboundExposesValidatedBoundedSettings() {
    var notBefore = Instant.parse("2026-07-28T00:00:00Z");
    var peer = new FederationOutboundProperties.ControlledPeer(
        "local-mastodon", URI.create("https://social.example/inbox"));
    var outbound = outbound(notBefore, List.of(peer));
    var properties = new FederationProperties(
        true,
        false,
        true,
        "site",
        "1.0",
        Base64.getEncoder().encodeToString(new byte[32]),
        outbound);

    org.junit.jupiter.api.Assertions.assertSame(outbound, properties.outbound());
  }

  @Test
  void outboundConfigurationRejectsMalformedOrUnboundedPeerUris() {
    for (var inbox : List.of(
        "http://social.example/inbox",
        "https://social.example:8443/inbox",
        "https://user:password@social.example/inbox",
        "https://social.example/inbox?shared=true",
        "https://social.example/inbox#fragment",
        "https://social.example")) {
      assertThrows(IllegalArgumentException.class, () -> outbound(
          Instant.parse("2026-07-28T00:00:00Z"),
          List.of(new FederationOutboundProperties.ControlledPeer("peer", URI.create(inbox)))));
    }
  }

  @Test
  void applicationYamlSuppliesSafeOutboundDefaults() {
    new ApplicationContextRunner()
        .withInitializer(context -> {
          try {
            var sources = new YamlPropertySourceLoader().load(
                "application", new ClassPathResource("application.yml"));
            sources.forEach(context.getEnvironment().getPropertySources()::addLast);
          } catch (java.io.IOException failure) {
            throw new IllegalStateException("Could not load application.yml", failure);
          }
        })
        .withUserConfiguration(PropertiesConfiguration.class)
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getEnvironment().getProperty(
              "app.federation.outbound.connect-timeout")).isEqualTo("3s");
          var outbound = context.getBean(FederationProperties.class).outbound();
          assertThat(outbound.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
          assertThat(outbound.peers()).isEmpty();
        });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(FederationProperties.class)
  static class PropertiesConfiguration {}

  private static FederationOutboundProperties outbound(
      Instant notBefore,
      List<FederationOutboundProperties.ControlledPeer> peers
  ) {
    return new FederationOutboundProperties(
        notBefore,
        peers,
        Duration.ofSeconds(3),
        Duration.ofSeconds(10),
        Duration.ofSeconds(30),
        Duration.ofHours(6),
        6,
        10,
        false);
  }
}
