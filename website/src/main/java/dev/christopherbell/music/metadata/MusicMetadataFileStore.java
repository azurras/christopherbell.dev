package dev.christopherbell.music.metadata;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Constrains all metadata backup and staging effects to one private same-volume root. */
public final class MusicMetadataFileStore {
  private final MusicMetadataProperties properties;
  private final Path musicRoot;

  public MusicMetadataFileStore(MusicMetadataProperties properties, Path musicRoot) {
    this.properties = properties;
    this.musicRoot = musicRoot.toAbsolutePath().normalize();
  }

  public Prepared prepare(Path source, String editId, String extension, byte[] artwork) {
    Path backup = null;
    Path stage = null;
    Path artworkFile = null;
    try {
      Path root = root(source);
      long size = Files.size(source);
      if (size < 1 || size > properties.maxSourceBytes()) {
        throw new IllegalStateException("Music source exceeds the metadata edit limit.");
      }
      backup = child(root, editId + ".backup." + extension);
      stage = child(root, editId + ".stage." + extension);
      Files.copy(source, backup, StandardCopyOption.COPY_ATTRIBUTES);
      String backupSha256 = sha256(backup);
      if (!backupSha256.equals(sha256(source))) {
        Files.deleteIfExists(backup);
        throw new IOException("Private metadata backup does not match the source.");
      }
      if (artwork != null) {
        artworkFile = child(root, editId + ".artwork");
        Files.write(artworkFile, artwork);
      }
      return new Prepared(
          backup.getFileName().toString(), backup, stage, artworkFile, backupSha256, size);
    } catch (IOException | SecurityException failure) {
      deletePath(stage);
      deletePath(artworkFile);
      deletePath(backup);
      throw new IllegalStateException("Private Music metadata staging is unavailable.", failure);
    }
  }

  public void validateStage(Prepared prepared) {
    try {
      if (Files.isSymbolicLink(prepared.stage())
          || !Files.isRegularFile(prepared.stage(), LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalStateException("Metadata rewrite did not create an ordinary file.");
      }
      long maximum = Math.addExact(prepared.sourceSize(), properties.maxExpansionBytes());
      long size = Files.size(prepared.stage());
      if (size < 1 || size > maximum) {
        throw new IllegalStateException("Metadata rewrite size is outside the allowed range.");
      }
    } catch (IOException | ArithmeticException failure) {
      throw new IllegalStateException("Metadata rewrite cannot be validated.", failure);
    }
  }

  public void atomicReplace(Path stage, Path destination) {
    try {
      Files.move(stage, destination,
          StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException failure) {
      throw new IllegalStateException("The Music volume does not support atomic metadata edits.", failure);
    } catch (IOException | SecurityException failure) {
      throw new IllegalStateException("Music metadata replacement failed.", failure);
    }
  }

  /** Gives an atomic replacement a revision timestamp distinct from the observed source. */
  public void markReplacement(Path stage, long previousModifiedMillis) {
    try {
      long next = Math.max(System.currentTimeMillis(), Math.addExact(previousModifiedMillis, 2_000));
      Files.setLastModifiedTime(stage, FileTime.fromMillis(next));
    } catch (IOException | ArithmeticException | SecurityException failure) {
      throw new IllegalStateException("Music metadata replacement revision cannot be prepared.", failure);
    }
  }

  public Path prepareUndo(MusicMetadataEdit edit, String operationId, String extension) {
    try {
      Path root = root(null);
      Path backup = child(root, edit.backupFileName());
      if (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)
          || Files.isSymbolicLink(backup) || !sha256(backup).equals(edit.backupSha256())) {
        throw new IllegalStateException("Music metadata backup failed its checksum validation.");
      }
      Path stage = child(root, operationId + ".undo." + extension);
      Files.copy(backup, stage);
      return stage;
    } catch (IOException | SecurityException failure) {
      throw new IllegalStateException("Music metadata backup is unavailable.", failure);
    }
  }

  public void delete(String fileName) {
    try {
      Files.deleteIfExists(child(root(null), fileName));
    } catch (IOException | SecurityException failure) {
      throw new IllegalStateException("Music metadata backup cleanup failed.", failure);
    }
  }

  public void cleanup(Prepared prepared, boolean keepBackup) {
    deletePath(prepared.stage());
    deletePath(prepared.artwork());
    if (!keepBackup) deletePath(prepared.backup());
  }

  private Path root(Path source) throws IOException {
    Path root = properties.privateRoot().toAbsolutePath().normalize();
    if (root.startsWith(musicRoot) || musicRoot.startsWith(root)) {
      throw new IOException("Private metadata storage must be outside the Music tree.");
    }
    Files.createDirectories(root);
    if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Private metadata root is unsafe.");
    }
    if (source != null && !Files.getFileStore(root).equals(Files.getFileStore(source))) {
      throw new IOException("Private metadata root must use the Music filesystem.");
    }
    return root;
  }

  private Path child(Path root, String name) throws IOException {
    if (name == null || !name.matches("[a-zA-Z0-9._-]{1,180}")) {
      throw new IOException("Private metadata file name is unsafe.");
    }
    Path child = root.resolve(name).normalize();
    if (!child.getParent().equals(root)) throw new IOException("Private metadata path escaped.");
    return child;
  }

  private String sha256(Path source) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (var input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable.", impossible);
    }
  }

  private void deletePath(Path path) {
    if (path == null) return;
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // The retention cleanup retries private leftovers without risking the source file.
    }
  }

  public record Prepared(
      String backupFileName,
      Path backup,
      Path stage,
      Path artwork,
      String backupSha256,
      long sourceSize) {
  }
}
