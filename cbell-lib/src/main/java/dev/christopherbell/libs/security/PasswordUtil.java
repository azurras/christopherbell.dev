package dev.christopherbell.libs.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Base64;
import lombok.experimental.UtilityClass;

/**
 * Utility class for password hashing and verification using PBKDF2 with HMAC-SHA256.
 */
@UtilityClass
public class PasswordUtil {
  private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
  private static final String FORMAT = "pbkdf2-sha256";
  private static final String FORMAT_PREFIX = FORMAT + "$";
  private static final int SALT_LENGTH = 16;
  private static final int LEGACY_ITERATIONS = 65_536;
  private static final int CURRENT_ITERATIONS = 210_000;
  private static final int MAX_ACCEPTED_ITERATIONS = 1_000_000;
  private static final int HASH_KEY_LENGTH = 256;
  private static final String VERIFICATION_PADDING_SALT = "AAAAAAAAAAAAAAAAAAAAAA==";

  /**
   * Generates a random salt.
   */
  public static String generateSalt() {
    var salt = new byte[SALT_LENGTH];
    new SecureRandom().nextBytes(salt);
    return Base64.getEncoder().encodeToString(salt);
  }

  /** Creates the current self-describing password hash format. */
  public static String hashPassword(String password)
      throws NoSuchAlgorithmException, InvalidKeySpecException {
    var salt = generateSalt();
    return encode(password, salt, CURRENT_ITERATIONS);
  }

  /**
   * Hashes a password with a given salt.
   */
  public static String hashPassword(String password, String salt)
      throws NoSuchAlgorithmException, InvalidKeySpecException {
    return derive(password, salt, LEGACY_ITERATIONS);
  }

  /**
   * Verifies a password against a stored hash.
   */
  public static boolean verifyPassword(String password, String salt, String storedHash)
      throws NoSuchAlgorithmException, InvalidKeySpecException {
    try {
      var encoded = parse(storedHash);
      if (storedHash != null && storedHash.startsWith(FORMAT_PREFIX) && encoded == null) {
        return false;
      }
      if (encoded == null && (salt == null || salt.isBlank())) {
        return false;
      }
      var expected = encoded == null
          ? derive(password, salt, LEGACY_ITERATIONS)
          : derive(password, encoded.salt(), encoded.iterations());
      padVerificationCost(password, encoded == null ? LEGACY_ITERATIONS : encoded.iterations());
      var actual = encoded == null ? storedHash : encoded.hash();
      return actual != null && MessageDigest.isEqual(
          expected.getBytes(StandardCharsets.US_ASCII),
          actual.getBytes(StandardCharsets.US_ASCII));
    } catch (IllegalArgumentException | NullPointerException malformed) {
      return false;
    }
  }

  /** Returns whether a verified credential should be rewritten in the current format. */
  public static boolean needsRehash(String legacySalt, String storedHash) {
    var encoded = parse(storedHash);
    return encoded == null
        || encoded.iterations() != CURRENT_ITERATIONS
        || (legacySalt != null && !legacySalt.isBlank());
  }

  /** Re-encodes a verified password deterministically so concurrent upgrades agree. */
  public static String upgradePassword(String password, String legacySalt, String storedHash)
      throws NoSuchAlgorithmException, InvalidKeySpecException {
    var encoded = parse(storedHash);
    var salt = encoded == null ? legacySalt : encoded.salt();
    if (salt == null || salt.isBlank()) {
      throw new IllegalArgumentException("Verified password hash has no reusable salt.");
    }
    return encode(password, salt, CURRENT_ITERATIONS);
  }

  static int verificationIterationsFor(String legacySalt, String storedHash) {
    var encoded = parse(storedHash);
    if (encoded != null) return Math.max(CURRENT_ITERATIONS, encoded.iterations());
    return legacySalt == null || legacySalt.isBlank() ? 0 : CURRENT_ITERATIONS;
  }

  private static String encode(String password, String salt, int iterations)
      throws NoSuchAlgorithmException, InvalidKeySpecException {
    return String.join(
        "$",
        FORMAT,
        Integer.toString(iterations),
        salt,
        derive(password, salt, iterations));
  }

  private static void padVerificationCost(String password, int completedIterations)
      throws NoSuchAlgorithmException, InvalidKeySpecException {
    var remainingIterations = CURRENT_ITERATIONS - completedIterations;
    if (remainingIterations > 0) {
      derive(password, VERIFICATION_PADDING_SALT, remainingIterations);
    }
  }

  private static String derive(String password, String salt, int iterations)
      throws NoSuchAlgorithmException, InvalidKeySpecException {
    var spec = new PBEKeySpec(
        password.toCharArray(),
        Base64.getDecoder().decode(salt),
        iterations,
        HASH_KEY_LENGTH);
    try {
      var factory = SecretKeyFactory.getInstance(ALGORITHM);
      return Base64.getEncoder().encodeToString(factory.generateSecret(spec).getEncoded());
    } finally {
      spec.clearPassword();
    }
  }

  private static EncodedHash parse(String storedHash) {
    if (storedHash == null || !storedHash.startsWith(FORMAT_PREFIX)) return null;
    var parts = storedHash.split("\\$", -1);
    if (parts.length != 4 || !FORMAT.equals(parts[0])) return null;
    try {
      var iterations = Integer.parseInt(parts[1]);
      if (iterations < 1 || iterations > MAX_ACCEPTED_ITERATIONS) return null;
      if (Base64.getDecoder().decode(parts[2]).length != SALT_LENGTH) return null;
      if (Base64.getDecoder().decode(parts[3]).length * Byte.SIZE != HASH_KEY_LENGTH) return null;
      return new EncodedHash(iterations, parts[2], parts[3]);
    } catch (IllegalArgumentException malformed) {
      return null;
    }
  }

  private record EncodedHash(int iterations, String salt, String hash) {}
}
