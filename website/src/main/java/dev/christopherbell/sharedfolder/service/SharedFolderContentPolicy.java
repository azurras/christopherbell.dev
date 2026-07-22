package dev.christopherbell.sharedfolder.service;

import dev.christopherbell.sharedfolder.model.SharedFolderPreviewKind;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;

/** Allowlist that keeps shared-folder previews inert unless their type is explicitly safe. */
final class SharedFolderContentPolicy {
  private SharedFolderContentPolicy() {}

  static SharedFolderPreviewKind previewKind(String filename) {
    return switch (extension(filename)) {
      case "txt", "log", "md", "csv", "json", "properties", "yaml", "yml" ->
          SharedFolderPreviewKind.TEXT;
      case "avif", "gif", "jpeg", "jpg", "png", "webp" -> SharedFolderPreviewKind.IMAGE;
      case "flac", "m4a", "mp3", "ogg", "wav" -> SharedFolderPreviewKind.AUDIO;
      case "mkv", "mp4", "ogv", "webm" -> SharedFolderPreviewKind.VIDEO;
      case "pdf" -> SharedFolderPreviewKind.PDF;
      default -> SharedFolderPreviewKind.NONE;
    };
  }

  static MediaType mediaType(String filename, SharedFolderPreviewKind kind) {
    return switch (kind) {
      case IMAGE -> switch (extension(filename)) {
        case "avif" -> MediaType.parseMediaType("image/avif");
        case "gif" -> MediaType.IMAGE_GIF;
        case "jpeg", "jpg" -> MediaType.IMAGE_JPEG;
        case "png" -> MediaType.IMAGE_PNG;
        case "webp" -> MediaType.parseMediaType("image/webp");
        default -> MediaType.APPLICATION_OCTET_STREAM;
      };
      case AUDIO -> switch (extension(filename)) {
        case "flac" -> MediaType.parseMediaType("audio/flac");
        case "m4a" -> MediaType.parseMediaType("audio/mp4");
        case "mp3" -> MediaType.parseMediaType("audio/mpeg");
        case "ogg" -> MediaType.parseMediaType("audio/ogg");
        case "wav" -> MediaType.parseMediaType("audio/wav");
        default -> MediaType.APPLICATION_OCTET_STREAM;
      };
      case VIDEO -> switch (extension(filename)) {
        case "mkv" -> MediaType.parseMediaType("video/x-matroska");
        case "mp4" -> MediaType.parseMediaType("video/mp4");
        case "ogv" -> MediaType.parseMediaType("video/ogg");
        case "webm" -> MediaType.parseMediaType("video/webm");
        default -> MediaType.APPLICATION_OCTET_STREAM;
      };
      case PDF -> MediaType.APPLICATION_PDF;
      case TEXT, NONE -> MediaType.APPLICATION_OCTET_STREAM;
    };
  }

  static String attachmentDisposition(String filename) {
    return ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString();
  }

  static String inlineDisposition(String filename) {
    return ContentDisposition.inline().filename(filename, StandardCharsets.UTF_8).build().toString();
  }

  private static String extension(String filename) {
    int dot = filename == null ? -1 : filename.lastIndexOf('.');
    return dot >= 0 && dot < filename.length() - 1
        ? filename.substring(dot + 1).toLowerCase(Locale.ROOT)
        : "";
  }
}
