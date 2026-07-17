package dev.christopherbell.sharedfolder.fs;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.springframework.core.io.AbstractResource;

/**
 * Disk-backed resource that revalidates its shared-folder target when Spring actually opens it.
 *
 * <p>Its public description deliberately omits local paths. A failed late recheck becomes a
 * generic not-found stream error rather than exposing filesystem details after response handling
 * has started.
 */
public final class SharedFolderReadResource extends AbstractResource {
  private final SharedFolderPathResolver.ReadHandle handle;
  private final String filename;
  private final long contentLength;

  /** Creates a resource from a validated handle and safe response metadata. */
  public SharedFolderReadResource(
      SharedFolderPathResolver.ReadHandle handle, String filename, long contentLength) {
    this.handle = Objects.requireNonNull(handle, "read handle is required");
    this.filename = Objects.requireNonNull(filename, "filename is required");
    this.contentLength = contentLength;
  }

  @Override
  public InputStream getInputStream() throws IOException {
    try {
      return handle.openFile();
    } catch (UnsafeSharedPathException exception) {
      throw new FileNotFoundException("Shared-folder item is no longer available");
    }
  }

  @Override
  public long contentLength() {
    return contentLength;
  }

  @Override
  public String getFilename() {
    return filename;
  }

  @Override
  public String getDescription() {
    return "shared-folder read resource";
  }
}
