package dev.christopherbell.federation.configuration;

import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Fail-closed switches and public metadata for ActivityPub federation. */
@ConfigurationProperties("app.federation")
public final class FederationProperties {
  private static final int ENCRYPTION_SECRET_BYTES = 32;
  private static final int MAX_METADATA_LENGTH = 100;

  private final boolean discoveryEnabled;
  private final boolean inboundEnabled;
  private final boolean outboundEnabled;
  private final String softwareName;
  private final String softwareVersion;
  private final byte[] encryptionSecret;
  private final FederationOutboundProperties outbound;

  public FederationProperties(
      boolean discoveryEnabled,
      boolean inboundEnabled,
      boolean outboundEnabled,
      String softwareName,
      String softwareVersion,
      String keyEncryptionSecret,
      FederationOutboundProperties outbound
  ) {
    if (!discoveryEnabled && (inboundEnabled || outboundEnabled)) {
      throw new IllegalArgumentException(
          "Federation discovery must be enabled before inbound or outbound federation");
    }
    this.discoveryEnabled = discoveryEnabled;
    this.inboundEnabled = inboundEnabled;
    this.outboundEnabled = outboundEnabled;
    this.softwareName = requireMetadata(softwareName, "software name");
    this.softwareVersion = requireMetadata(softwareVersion, "software version");
    this.encryptionSecret = discoveryEnabled ? decodeRequiredSecret(keyEncryptionSecret) : null;
    this.outbound = outbound == null ? FederationOutboundProperties.defaults() : outbound;
    if (outboundEnabled
        && (this.outbound.notBefore() == null || this.outbound.peers().isEmpty())) {
      throw new IllegalArgumentException(
          "Federation outbound requires a not-before time and at least one controlled peer");
    }
  }

  public boolean discoveryEnabled() {
    return discoveryEnabled;
  }

  public boolean inboundEnabled() {
    return inboundEnabled;
  }

  public boolean outboundEnabled() {
    return outboundEnabled;
  }

  public String softwareName() {
    return softwareName;
  }

  public String softwareVersion() {
    return softwareVersion;
  }

  public FederationOutboundProperties outbound() {
    return outbound;
  }

  public byte[] requiredEncryptionSecret() {
    if (encryptionSecret == null) {
      throw new IllegalStateException("Federation discovery is disabled");
    }
    return encryptionSecret.clone();
  }

  private static byte[] decodeRequiredSecret(String encodedSecret) {
    if (encodedSecret == null || encodedSecret.isBlank()) {
      throw invalidSecret();
    }
    try {
      byte[] decoded = Base64.getDecoder().decode(encodedSecret);
      if (decoded.length != ENCRYPTION_SECRET_BYTES) {
        throw invalidSecret();
      }
      return decoded;
    } catch (IllegalArgumentException exception) {
      throw invalidSecret();
    }
  }

  private static IllegalArgumentException invalidSecret() {
    return new IllegalArgumentException(
        "Federation key-encryption secret must be base64 for exactly 32 bytes");
  }

  private static String requireMetadata(String value, String label) {
    if (value == null || value.isBlank() || value.length() > MAX_METADATA_LENGTH) {
      throw new IllegalArgumentException(
          "Federation " + label + " must contain between 1 and 100 characters");
    }
    return value;
  }
}
