package dev.christopherbell.music.catalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Exact-enough disk observation used to reject stale catalog rows before playback. */
public record MusicFileRevision(long size, long modifiedMillis, String token) {

  public static MusicFileRevision observe(Path source) throws IOException {
    if (source == null || Files.isSymbolicLink(source)
        || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Music source is not an ordinary file.");
    }
    BasicFileAttributes attributes = Files.readAttributes(
        source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    return observe(attributes);
  }

  /** Creates the same revision from attributes obtained through a held, revalidating handle. */
  public static MusicFileRevision observe(BasicFileAttributes attributes) {
    if (attributes == null || !attributes.isRegularFile()
        || attributes.isSymbolicLink() || attributes.isOther()) {
      throw new IllegalArgumentException("Music source is not an ordinary file.");
    }
    String material = String.valueOf(attributes.fileKey()) + ':' + attributes.size() + ':'
        + attributes.lastModifiedTime().toMillis();
    return new MusicFileRevision(
        attributes.size(), attributes.lastModifiedTime().toMillis(), hash(material));
  }

  private static String hash(String material) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(material.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable.", impossible);
    }
  }
}
