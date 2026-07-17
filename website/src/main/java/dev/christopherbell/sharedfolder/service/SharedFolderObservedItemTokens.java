package dev.christopherbell.sharedfolder.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;

/** Creates opaque relative-item observations without retaining or exposing local absolute paths. */
public final class SharedFolderObservedItemTokens {
  private SharedFolderObservedItemTokens() {}

  public static String token(String relativePath, BasicFileAttributes attributes) {
    String identity = attributes.fileKey() == null
        ? attributes.creationTime() + ":" + attributes.size() + ":" + attributes.lastModifiedTime()
        : attributes.fileKey().toString();
    return token(relativePath, identity, attributes.isDirectory(), attributes.size(),
        attributes.lastModifiedTime().toInstant());
  }

  public static String token(
      String relativePath, String identity, boolean directory, long size, Instant modifiedAt) {
    String payload = relativePath + "\n" + identity + "\n" + directory + "\n"
        + size + "\n" + modifiedAt;
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(payload.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required", exception);
    }
  }

  public static boolean matches(String expected, String actual) {
    return expected != null && actual != null && MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
  }
}
