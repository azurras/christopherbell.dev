package dev.christopherbell.sharedfolder.media;

import dev.christopherbell.sharedfolder.media.MediaSourceBoundary.MediaSourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable opaque cache identities bound to source revision and a fixed profile version. */
public final class MediaCacheKeys {
  private MediaCacheKeys() {}

  public static String forSource(
      MediaSourceSnapshot source, MediaOutputProfile profile, int profileVersion) {
    String material = source.relativePath() + "\n" + source.size() + "\n"
        + source.modifiedAt() + "\n" + profile.name() + "\n" + profileVersion;
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
