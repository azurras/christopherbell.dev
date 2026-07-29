package dev.christopherbell.federation.configuration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class FederationPropertiesTest {

  @Test
  void disabledFederationDoesNotRequireASecret() {
    new FederationProperties(false, false, false, "christopherbell.dev", "1.0", null);
    new FederationProperties(false, false, false, "christopherbell.dev", "1.0", "not-base64");
  }

  @Test
  void inboundAndOutboundCannotRunWithoutDiscovery() {
    assertThrows(IllegalArgumentException.class,
        () -> new FederationProperties(false, true, false, "site", "1.0", null));
    assertThrows(IllegalArgumentException.class,
        () -> new FederationProperties(false, false, true, "site", "1.0", null));
  }

  @Test
  void enabledDiscoveryRequiresAValidAes256Secret() {
    assertThrows(IllegalArgumentException.class,
        () -> new FederationProperties(true, false, false, "site", "1.0", null));
    assertThrows(IllegalArgumentException.class,
        () -> new FederationProperties(true, false, false, "site", "1.0", "not-base64"));
    assertThrows(IllegalArgumentException.class,
        () -> new FederationProperties(
            true, false, false, "site", "1.0", Base64.getEncoder().encodeToString(new byte[31])));
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
        Base64.getEncoder().encodeToString(expected));

    byte[] first = properties.requiredEncryptionSecret();
    first[0] = 0;

    assertArrayEquals(expected, properties.requiredEncryptionSecret());
  }
}
