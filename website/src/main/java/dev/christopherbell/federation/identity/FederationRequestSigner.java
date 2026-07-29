package dev.christopherbell.federation.identity;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.federation.outbound.FederationRequestTarget;
import dev.christopherbell.federation.outbound.SignedFederationRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Signs exact outbound bytes using an actor's encrypted local RSA key. */
public final class FederationRequestSigner {
  private static final String DIGEST_ALGORITHM = "SHA-256";
  private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
  private static final String SIGNED_HEADERS = "(request-target) host date digest";

  private final FederationPrivateKeyDecryptor privateKeys;
  private final Clock clock;

  public FederationRequestSigner(FederationPrivateKeyDecryptor privateKeys, Clock clock) {
    this.privateKeys = Objects.requireNonNull(privateKeys, "privateKeys");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public SignedFederationRequest sign(Account account, URI inbox, byte[] body) {
    Objects.requireNonNull(account, "account");
    Objects.requireNonNull(body, "body");
    if (account.getId() == null || account.getId().isBlank()) {
      throw new FederationRequestSigningException("Federation signing account ID is unavailable");
    }
    FederationIdentity identity = account.getFederationIdentity();
    if (identity == null) {
      throw new FederationRequestSigningException("Federation signing identity is unavailable");
    }
    var target = new FederationRequestTarget(inbox);
    String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(
        ZonedDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    byte[] digestBytes;
    try {
      digestBytes = MessageDigest.getInstance(DIGEST_ALGORITHM).digest(body);
    } catch (GeneralSecurityException failure) {
      throw new FederationRequestSigningException("Federation request digest failed", failure);
    }
    String digest = DIGEST_ALGORITHM + "=" + Base64.getEncoder().encodeToString(digestBytes);
    String signingString = "(request-target): post " + target.requestTarget() + "\n"
        + "host: " + target.hostHeader() + "\n"
        + "date: " + date + "\n"
        + "digest: " + digest;

    byte[] pkcs8 = null;
    try {
      pkcs8 = privateKeys.decrypt(account.getId(), identity);
      if (pkcs8 == null || pkcs8.length == 0) {
        throw new FederationRequestSigningException("Federation signing key is unavailable");
      }
      var privateKey = KeyFactory.getInstance("RSA")
          .generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
      var signature = Signature.getInstance(SIGNATURE_ALGORITHM);
      signature.initSign(privateKey);
      signature.update(signingString.getBytes(StandardCharsets.US_ASCII));
      String encodedSignature = Base64.getEncoder().encodeToString(signature.sign());
      var headers = new LinkedHashMap<String, String>();
      headers.put("Content-Type", "application/activity+json");
      headers.put("Date", date);
      headers.put("Digest", digest);
      headers.put("Signature", signatureHeader(identity.keyId(), encodedSignature));
      return new SignedFederationRequest(headers, body);
    } catch (GeneralSecurityException | IllegalArgumentException failure) {
      throw new FederationRequestSigningException("Federation request signing failed", failure);
    } finally {
      if (pkcs8 != null) {
        Arrays.fill(pkcs8, (byte) 0);
      }
    }
  }

  private static String signatureHeader(String keyId, String signature) {
    String escapedKeyId = keyId.replace("\\", "\\\\").replace("\"", "\\\"");
    return "keyId=\"" + escapedKeyId + "\",algorithm=\"rsa-sha256\",headers=\""
        + SIGNED_HEADERS + "\",signature=\"" + signature + "\"";
  }
}
