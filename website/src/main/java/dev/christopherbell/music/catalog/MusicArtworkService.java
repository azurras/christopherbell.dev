package dev.christopherbell.music.catalog;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;

/** Extracts bounded embedded artwork into a private revision-addressed cache. */
public class MusicArtworkService {
  private final MusicProperties properties;
  private final MusicProcessRunner runner;

  public MusicArtworkService(MusicProperties properties, MusicProcessRunner runner) {
    this.properties = properties;
    this.runner = runner;
  }

  public Optional<String> extract(
      Path source,
      String relativePath,
      MusicFileRevision revision) {
    String artworkRevision = hash(relativePath + '\n' + revision.token());
    Path temporary = null;
    try {
      Path cacheRoot = safeCacheRoot();
      Path destination = cacheRoot.resolve(artworkRevision + ".jpg").normalize();
      if (!destination.startsWith(cacheRoot)) return Optional.empty();
      if (Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
        return Optional.of(artworkRevision);
      }
      temporary = cacheRoot.resolve("." + artworkRevision + '-' + UUID.randomUUID() + ".tmp.jpg");
      var result = runner.run(List.of(
          properties.ffmpegCommand(), "-nostdin", "-v", "error", "-y",
          "-i", source.toAbsolutePath().normalize().toString(),
          "-map", "0:v:0", "-frames:v", "1",
          "-vf", "scale=512:512:force_original_aspect_ratio=decrease",
          temporary.toString()));
      if (result.timedOut() || result.outputTruncated() || result.exitCode() != 0
          || result.stderr() == null || !result.stderr().isBlank()
          || !validImage(temporary)) {
        return Optional.empty();
      }
      try {
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
        Files.move(temporary, destination);
      }
      return Optional.of(artworkRevision);
    } catch (IOException | RuntimeException failure) {
      return Optional.empty();
    } finally {
      try {
        if (temporary != null) Files.deleteIfExists(temporary);
      } catch (IOException ignored) {
        // A later cache cleanup removes abandoned private temporary files.
      }
    }
  }

  public Optional<Path> resolve(String artworkRevision) {
    if (artworkRevision == null || !artworkRevision.matches("[a-f0-9]{64}")) {
      return Optional.empty();
    }
    Path root = safeCacheRoot();
    Path candidate = root.resolve(artworkRevision + ".jpg").normalize();
    return candidate.startsWith(root) && Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
        ? Optional.of(candidate)
        : Optional.empty();
  }

  private Path safeCacheRoot() {
    try {
      Path root = properties.artworkCacheRoot().toAbsolutePath().normalize();
      Files.createDirectories(root);
      if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalStateException("Music artwork cache root is unsafe.");
      }
      return root;
    } catch (IOException failure) {
      throw new IllegalStateException("Music artwork cache root is unavailable.", failure);
    }
  }

  private boolean validImage(Path path) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false;
    long size = Files.size(path);
    if (size < 1 || size > properties.artworkMaxBytes()) return false;
    BufferedImage image = ImageIO.read(path.toFile());
    return image != null
        && image.getWidth() > 0
        && image.getHeight() > 0
        && image.getWidth() <= properties.artworkMaxDimension()
        && image.getHeight() <= properties.artworkMaxDimension();
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
