package dev.christopherbell.account.auth;

import dev.christopherbell.account.model.Account;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

/** Stable digest of account state whose mutation must revoke credentials. */
public final class AccountSecurityFingerprint {
  public static final String CLAIM = "account-security";

  private AccountSecurityFingerprint() {}

  /** Returns a deterministic digest without exposing credential or permission values. */
  public static String from(Account account) {
    var source = new StringBuilder()
        .append(account.getId()).append('\n')
        .append(account.getPasswordHash()).append('\n')
        .append(account.getRole()).append('\n')
        .append(account.getStatus()).append('\n');
    if (account.getPermissions() != null) {
      account.getPermissions().stream()
          .sorted(Comparator.comparing(Enum::name))
          .forEach(permission -> source.append(permission.name()).append('\n'));
    }
    return hash(source.toString());
  }

  /** Compares a presented fingerprint without data-dependent string comparison. */
  public static boolean matches(String expected, Account account) {
    if (expected == null || account == null) return false;
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII),
        from(account).getBytes(StandardCharsets.US_ASCII));
  }

  private static String hash(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable.", impossible);
    }
  }
}
