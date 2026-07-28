package dev.christopherbell.music.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Resolves Music media commands through the protected, hash-pinned installation marker. */
public final class MusicExecutableResolver {
  private static final int MAX_MARKER_BYTES = 16_384;
  private static final int MAX_TOOL_DEPTH = 8;
  private static final Pattern VERSION_DIRECTORY = Pattern.compile("[A-Za-z0-9._-]{1,120}");
  private static final Pattern SHA256 = Pattern.compile("[A-Fa-f0-9]{64}");
  private static final Set<String> MARKER_FIELDS = Set.of(
      "schemaVersion", "packageVersion", "packageSha256", "versionDirectory",
      "ffmpegSha256", "ffprobeSha256");

  private final boolean enabled;
  private final Map<String, String> commands;

  public MusicExecutableResolver(
      boolean enabled,
      String ffmpegCommand,
      String ffprobeCommand,
      MusicMediaToolProperties properties,
      ObjectMapper objectMapper) {
    this.enabled = enabled;
    if (!enabled) {
      commands = Map.of(ffmpegCommand, ffmpegCommand, ffprobeCommand, ffprobeCommand);
      return;
    }
    commands = Map.of(
        ffmpegCommand, resolveConfigured(ffmpegCommand, "ffmpeg.exe", properties, objectMapper),
        ffprobeCommand, resolveConfigured(ffprobeCommand, "ffprobe.exe", properties, objectMapper));
  }

  public String resolve(String configuredCommand) {
    String resolved = commands.get(configuredCommand);
    if (resolved != null) return resolved;
    if (!enabled) return configuredCommand;
    throw new IllegalArgumentException("Music media command is outside the configured tool set.");
  }

  static MusicExecutableResolver identity() {
    return new MusicExecutableResolver(
        false, "ffmpeg", "ffprobe", new MusicMediaToolProperties(Path.of(".")),
        new ObjectMapper());
  }

  private String resolveConfigured(
      String configured,
      String executableName,
      MusicMediaToolProperties properties,
      ObjectMapper objectMapper) {
    Path configuredPath = Path.of(configured);
    if (configuredPath.isAbsolute()) {
      Path executable = configuredPath.normalize();
      requireSafeRegularFile(executable, "Configured Music executable is unsafe.");
      return executable.toString();
    }
    String expectedBareName = executableName.substring(0, executableName.length() - 4);
    if (!configured.equalsIgnoreCase(expectedBareName)) {
      throw new IllegalStateException(
          "Enabled Music commands must use the pinned tool set or an absolute path.");
    }
    return pinned(properties.root(), executableName, objectMapper).toString();
  }

  private Path pinned(Path configuredRoot, String executableName, ObjectMapper objectMapper) {
    Path root = configuredRoot.toAbsolutePath().normalize();
    requireSafeDirectory(root, "Pinned Music media-tools root is unavailable or unsafe.");
    Path marker = root.resolve("active-media-tools.json");
    requireSafeRegularFile(marker, "Pinned Music media-tools marker is unavailable or unsafe.");
    try {
      if (Files.size(marker) > MAX_MARKER_BYTES) {
        throw new IllegalStateException("Pinned Music media-tools marker is too large.");
      }
      JsonNode json = objectMapper.readTree(Files.readString(marker, StandardCharsets.UTF_8));
      if (!json.isObject() || json.size() != MARKER_FIELDS.size()
          || MARKER_FIELDS.stream().anyMatch(field -> !json.has(field))
          || json.path("schemaVersion").asInt(-1) != 1) {
        throw new IllegalStateException("Pinned Music media-tools marker fields are invalid.");
      }
      String packageVersion = json.path("packageVersion").asText("");
      String packageHash = json.path("packageSha256").asText("");
      if (!VERSION_DIRECTORY.matcher(packageVersion).matches()
          || !SHA256.matcher(packageHash).matches()) {
        throw new IllegalStateException("Pinned Music media-tools package identity is invalid.");
      }
      String versionDirectory = json.path("versionDirectory").asText("");
      if (!VERSION_DIRECTORY.matcher(versionDirectory).matches()) {
        throw new IllegalStateException("Pinned Music media-tools version is invalid.");
      }
      Path versionRoot = root.resolve("versions").resolve(versionDirectory).normalize();
      if (!versionRoot.startsWith(root.resolve("versions").normalize())) {
        throw new IllegalStateException("Pinned Music media-tools version escaped its root.");
      }
      requireSafeDirectory(versionRoot, "Pinned Music media-tools version is unavailable or unsafe.");
      List<Path> matches = findExecutables(versionRoot, executableName);
      if (matches.size() != 1) {
        throw new IllegalStateException(
            "Pinned Music media-tools version must contain exactly one " + executableName + '.');
      }
      Path executable = matches.getFirst();
      String hashField = executableName.equalsIgnoreCase("ffmpeg.exe")
          ? "ffmpegSha256" : "ffprobeSha256";
      String expectedHash = json.path(hashField).asText("");
      if (!SHA256.matcher(expectedHash).matches()
          || !MessageDigest.isEqual(
              expectedHash.toUpperCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII),
              sha256(executable).getBytes(StandardCharsets.US_ASCII))) {
        throw new IllegalStateException("Pinned Music media-tools executable hash is invalid.");
      }
      return executable.toAbsolutePath().normalize();
    } catch (IllegalStateException failure) {
      throw failure;
    } catch (IOException failure) {
      throw new IllegalStateException("Pinned Music media-tools could not be verified.", failure);
    }
  }

  private List<Path> findExecutables(Path root, String executableName) throws IOException {
    var matches = new ArrayList<Path>();
    try (var paths = Files.walk(root, MAX_TOOL_DEPTH)) {
      List<Path> boundedPaths = paths.limit(10_001).toList();
      if (boundedPaths.size() > 10_000) {
        throw new IOException("Pinned Music media-tools tree is unexpectedly large.");
      }
      for (Path path : boundedPaths) {
        requireNotLinkOrReparse(path);
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            && path.getFileName().toString().equalsIgnoreCase(executableName)) {
          matches.add(path);
        }
      }
    }
    return List.copyOf(matches);
  }

  private void requireSafeDirectory(Path path, String message) {
    try {
      requireNoLinkOrReparseComponents(path);
      if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException(message);
    } catch (IOException failure) {
      throw new IllegalStateException(message, failure);
    }
  }

  private void requireSafeRegularFile(Path path, String message) {
    try {
      requireNoLinkOrReparseComponents(path);
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException(message);
    } catch (IOException failure) {
      throw new IllegalStateException(message, failure);
    }
  }

  private void requireNotLinkOrReparse(Path path) throws IOException {
    BasicFileAttributes attributes = Files.readAttributes(
        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (attributes.isSymbolicLink() || attributes.isOther()) {
      throw new IOException("Music media-tools path contains a link or reparse point.");
    }
  }

  private void requireNoLinkOrReparseComponents(Path path) throws IOException {
    Path absolute = path.toAbsolutePath().normalize();
    Path current = absolute.getRoot();
    if (current != null) requireNotLinkOrReparse(current);
    for (Path segment : absolute) {
      current = current == null ? segment : current.resolve(segment);
      requireNotLinkOrReparse(current);
    }
  }

  private String sha256(Path path) throws IOException {
    try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
      return HexFormat.of().withUpperCase().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable.", impossible);
    }
  }
}
