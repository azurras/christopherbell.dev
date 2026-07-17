package dev.christopherbell.sharedfolder.service;

import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.sharedfolder.fs.SharedFolderReadResource;
import dev.christopherbell.sharedfolder.fs.SharedFolderPathResolver;
import dev.christopherbell.sharedfolder.fs.SharedFolderPathResolver.ReadHandle;
import dev.christopherbell.sharedfolder.fs.UnsafeSharedPathException;
import dev.christopherbell.sharedfolder.model.SharedFolderPreviewKind;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributes;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Opens bounded text previews and allowlisted media previews without in-memory file reads. */
@Service
public class SharedFolderPreviewService {
  private static final int MAX_TEXT_PREVIEW_BYTES = 64 * 1024;
  private static final int MAX_TEXT_PREVIEW_LINES = 2_000;

  private final SharedFolderProperties properties;

  /** Creates the preview service from the configured shared-folder boundary. */
  public SharedFolderPreviewService(SharedFolderProperties properties) {
    this.properties = properties;
  }

  /**
   * Opens one decoded relative file path for an allowlisted preview mode.
   *
   * <p>HTML and SVG never become inline preview types. Text is bounded and returned separately so
   * callers can render it with a text node rather than HTML.
   *
   * @param decodedPath decoded relative file path
   * @return bounded text or a disk-backed media resource with safe presentation metadata
   */
  public SharedFolderPreview open(String decodedPath) {
    ResolvedFile file = resolveFile(decodedPath);
    String name = file.name();
    SharedFolderPreviewKind kind = SharedFolderContentPolicy.previewKind(name);
    try {
      if (kind == SharedFolderPreviewKind.TEXT) {
        BoundedText text = readBoundedUtf8(file.handle());
        return SharedFolderPreview.text(text.value(), text.truncated());
      }
      MediaType mediaType = SharedFolderContentPolicy.mediaType(name, kind);
      String disposition = kind == SharedFolderPreviewKind.NONE
          ? SharedFolderContentPolicy.attachmentDisposition(name)
          : SharedFolderContentPolicy.inlineDisposition(name);
      return new SharedFolderPreview(kind, null,
          new SharedFolderReadResource(file.handle(), name, file.attributes().size()), false, mediaType,
          disposition);
    } catch (IOException exception) {
      throw notFound();
    }
  }

  private ResolvedFile resolveFile(String decodedPath) {
    if (!properties.enabled() || decodedPath == null) {
      throw notFound();
    }
    try {
      SharedFolderPathResolver resolver = new SharedFolderPathResolver(properties.root());
      ReadHandle handle = resolver.readHandle(resolver.existing(decodedPath));
      BasicFileAttributes attributes = handle.attributes();
      if (!attributes.isRegularFile()) {
        throw notFound();
      }
      String name = decodedPath.substring(decodedPath.lastIndexOf('/') + 1);
      return new ResolvedFile(handle, name, attributes);
    } catch (UnsafeSharedPathException exception) {
      throw notFound();
    }
  }

  private BoundedText readBoundedUtf8(ReadHandle file) throws IOException {
    byte[] bytes = new byte[MAX_TEXT_PREVIEW_BYTES];
    int count = 0;
    int lines = 0;
    boolean truncated = false;
    try (InputStream stream = file.openFile()) {
      while (count < bytes.length) {
        int next = stream.read();
        if (next < 0) {
          break;
        }
        bytes[count++] = (byte) next;
        if (next == '\n' && ++lines >= MAX_TEXT_PREVIEW_LINES) {
          truncated = stream.read() >= 0;
          break;
        }
      }
      if (count == bytes.length && !truncated) {
        truncated = stream.read() >= 0;
      }
    }
    return new BoundedText(new String(bytes, 0, count, StandardCharsets.UTF_8), truncated);
  }

  private ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Shared folder item was not found");
  }

  private record BoundedText(String value, boolean truncated) {}

  private record ResolvedFile(ReadHandle handle, String name, BasicFileAttributes attributes) {}

  /** Safe preview data that never exposes a local filesystem path. */
  public record SharedFolderPreview(
      SharedFolderPreviewKind kind,
      String text,
      Resource resource,
      boolean truncated,
      MediaType mediaType,
      String disposition) {
    /** Creates an escaped text preview payload. */
    public static SharedFolderPreview text(String text, boolean truncated) {
      return new SharedFolderPreview(
          SharedFolderPreviewKind.TEXT, text, null, truncated, MediaType.APPLICATION_JSON, null);
    }
  }
}
