package dev.christopherbell.sharedfolder.service;

import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.sharedfolder.fs.SharedFolderPathResolver;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderMutationBoundary;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeBoundaryException;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeFileMetadata;
import dev.christopherbell.sharedfolder.model.SharedDirectoryEntry;
import dev.christopherbell.sharedfolder.model.SharedDirectoryEntryType;
import dev.christopherbell.sharedfolder.model.SharedFolderCreateFolderRequest;
import dev.christopherbell.sharedfolder.model.SharedFolderDeleteRequest;
import dev.christopherbell.sharedfolder.model.SharedFolderMoveRequest;
import dev.christopherbell.sharedfolder.model.SharedFolderPreviewKind;
import dev.christopherbell.sharedfolder.model.SharedFolderRenameRequest;
import dev.christopherbell.sharedfolder.security.SharedFolderAccessService;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Performs conflict-safe shared-folder mutations after a fresh write authorization decision. */
@Service
public class SharedFolderMutationService {
  private final SharedFolderAccessService access;
  private final SharedFolderProperties properties;
  private final WindowsSharedFolderMutationBoundary nativeBoundary;

  /** Creates the portable mutation service used by non-Windows test and local providers. */
  public SharedFolderMutationService(
      SharedFolderAccessService access, SharedFolderProperties properties) {
    this(access, properties, WindowsSharedFolderMutationBoundary.inactive());
  }

  /** Creates the production service; Windows held-root mode must never fall back to NIO writes. */
  @Autowired
  public SharedFolderMutationService(
      SharedFolderAccessService access,
      SharedFolderProperties properties,
      WindowsSharedFolderMutationBoundary nativeBoundary) {
    this.access = access;
    this.properties = properties;
    this.nativeBoundary = nativeBoundary;
  }

  /** Creates one new directory under a decoded, existing relative parent path. */
  public SharedDirectoryEntry createFolder(SharedFolderCreateFolderRequest request) {
    access.requireWrite();
    if (request == null || !properties.enabled()) {
      throw notFound();
    }
    if (nativeBoundary.nativeMode()) {
      try {
        String relative = relativePath(request.parentPath(), request.name());
        return describeNative(relative,
            nativeBoundary.createDirectory(request.parentPath(), request.name()));
      } catch (NativeBoundaryException | SecurityException exception) {
        throw conflict();
      }
    }
    try {
      SharedFolderPathResolver resolver = new SharedFolderPathResolver(properties.root());
      Path target = resolver.newChild(request.parentPath(), request.name());
      Path parent = target.getParent();
      resolver.recheckForMutation(parent);
      Files.createDirectory(target);
      BasicFileAttributes attributes = Files.readAttributes(target, BasicFileAttributes.class);
      return new SharedDirectoryEntry(
          request.name(), relativePath(request.parentPath(), request.name()),
          SharedDirectoryEntryType.DIRECTORY, 0, attributes.lastModifiedTime().toInstant(),
          SharedFolderPreviewKind.NONE,
          SharedFolderObservedItemTokens.token(relativePath(request.parentPath(), request.name()), attributes));
    } catch (FileAlreadyExistsException exception) {
      throw conflict();
    } catch (IOException | SecurityException exception) {
      throw notFound();
    }
  }

  /** Returns an opaque observation for a currently existing relative item. */
  public String observedToken(String path) {
    if (!properties.enabled()) {
      throw notFound();
    }
    try {
      if (nativeBoundary.nativeMode()) {
        return nativeToken(path, nativeBoundary.metadata(path));
      }
      SharedFolderPathResolver resolver = new SharedFolderPathResolver(properties.root());
      return currentToken(resolver, path, resolver.existing(path));
    } catch (SecurityException exception) {
      throw notFound();
    }
  }

  /** Renames one observed item without implicitly replacing an existing target. */
  public SharedDirectoryEntry rename(SharedFolderRenameRequest request) {
    access.requireWrite();
    if (request == null || !properties.enabled()) {
      throw notFound();
    }
    String parentPath = parentPath(request.path());
    String targetRelative = relativePath(parentPath, request.name());
    if (nativeBoundary.nativeMode()) {
      try {
        NativeFileMetadata observed = nativeBoundary.metadata(request.path());
        requireNativeToken(request.path(), observed, request.observedToken());
        return describeNative(targetRelative, nativeBoundary.rename(
            request.path(), parentPath, request.name(), false, observed));
      } catch (ResponseStatusException exception) {
        throw exception;
      } catch (NativeBoundaryException | SecurityException exception) {
        throw conflict();
      }
    }
    try {
      SharedFolderPathResolver resolver = new SharedFolderPathResolver(properties.root());
      Path source = resolver.existing(request.path());
      Path target = resolver.newChild(parentPath, request.name());
      boolean caseOnly = !targetRelative.equals(request.path())
          && targetRelative.equalsIgnoreCase(request.path());
      if (!caseOnly && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        throw conflict();
      }
      requireCurrentToken(resolver, request.path(), source, request.observedToken());
      resolver.recheckForMutation(source);
      resolver.recheckForMutation(source.getParent());
      requireCurrentToken(resolver, request.path(), source, request.observedToken());
      if (caseOnly) {
        moveCaseOnly(source, target, resolver);
      } else {
        moveAtomically(source, target, false);
      }
      return describe(targetRelative, target);
    } catch (FileAlreadyExistsException exception) {
      throw conflict();
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (IOException | SecurityException exception) {
      throw notFound();
    }
  }

  /** Moves one observed item and permits replacement only with an explicit target observation. */
  public SharedDirectoryEntry move(SharedFolderMoveRequest request) {
    access.requireWrite();
    if (request == null || !properties.enabled()) {
      throw notFound();
    }
    String targetRelative = relativePath(request.destinationPath(), request.name());
    if (nativeBoundary.nativeMode()) {
      try {
        if (targetRelative.equals(request.path())) {
          throw conflict();
        }
        NativeFileMetadata observed = nativeBoundary.metadata(request.path());
        requireNativeToken(request.path(), observed, request.observedToken());
        NativeFileMetadata replaced = null;
        if (request.replace()) {
          if (request.replacedObservedToken() == null) {
            throw conflict();
          }
          replaced = nativeBoundary.metadata(targetRelative);
          requireNativeToken(targetRelative, replaced, request.replacedObservedToken());
        }
        return describeNative(targetRelative, nativeBoundary.rename(
            request.path(), request.destinationPath(), request.name(), request.replace(), observed,
            replaced));
      } catch (ResponseStatusException exception) {
        throw exception;
      } catch (NativeBoundaryException | SecurityException exception) {
        throw conflict();
      }
    }
    try {
      SharedFolderPathResolver resolver = new SharedFolderPathResolver(properties.root());
      Path source = resolver.existing(request.path());
      Path target = resolver.newChild(request.destinationPath(), request.name());
      if (targetRelative.equals(request.path())) {
        throw conflict();
      }
      boolean targetExists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
      Path existingTarget = null;
      if (targetExists) {
        if (!request.replace() || request.replacedObservedToken() == null) {
          throw conflict();
        }
        existingTarget = resolver.existing(targetRelative);
        requireCurrentToken(resolver, targetRelative, existingTarget, request.replacedObservedToken());
      }
      requireCurrentToken(resolver, request.path(), source, request.observedToken());
      resolver.recheckForMutation(source);
      resolver.recheckForMutation(target.getParent());
      requireSameFileStore(source, target.getParent());
      requireCurrentToken(resolver, request.path(), source, request.observedToken());
      if (existingTarget != null) {
        requireCurrentToken(resolver, targetRelative, existingTarget, request.replacedObservedToken());
      }
      moveAtomically(source, target, request.replace());
      return describe(targetRelative, target);
    } catch (FileAlreadyExistsException exception) {
      throw conflict();
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (IOException | SecurityException exception) {
      throw notFound();
    }
  }

  /** Physically deletes an observed item until the later recycle layer replaces this seam. */
  public void delete(SharedFolderDeleteRequest request) {
    access.requireWrite();
    if (request == null || !properties.enabled()) {
      throw notFound();
    }
    if (nativeBoundary.nativeMode()) {
      try {
        NativeFileMetadata observed = nativeBoundary.metadata(request.path());
        requireNativeToken(request.path(), observed, request.observedToken());
        nativeBoundary.delete(request.path(), observed);
        return;
      } catch (ResponseStatusException exception) {
        throw exception;
      } catch (NativeBoundaryException | SecurityException exception) {
        throw conflict();
      }
    }
    try {
      SharedFolderPathResolver resolver = new SharedFolderPathResolver(properties.root());
      Path source = resolver.existing(request.path());
      requireCurrentToken(resolver, request.path(), source, request.observedToken());
      resolver.recheckForMutation(source);
      requireCurrentToken(resolver, request.path(), source, request.observedToken());
      Files.delete(source);
    } catch (DirectoryNotEmptyException exception) {
      throw conflict();
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (IOException | SecurityException exception) {
      throw notFound();
    }
  }

  private void requireCurrentToken(
      SharedFolderPathResolver resolver, String relative, Path path, String suppliedToken) {
    if (!SharedFolderObservedItemTokens.matches(suppliedToken, currentToken(resolver, relative, path))) {
      throw conflict();
    }
  }

  private String currentToken(SharedFolderPathResolver resolver, String relative, Path path) {
    try {
      return SharedFolderObservedItemTokens.token(relative, resolver.readHandle(path).attributes());
    } catch (SecurityException exception) {
      throw notFound();
    }
  }

  private SharedDirectoryEntry describe(String relative, Path target) throws IOException {
    BasicFileAttributes attributes = Files.readAttributes(target, BasicFileAttributes.class,
        LinkOption.NOFOLLOW_LINKS);
    SharedDirectoryEntryType type = attributes.isDirectory()
        ? SharedDirectoryEntryType.DIRECTORY : SharedDirectoryEntryType.FILE;
    return new SharedDirectoryEntry(
        target.getFileName().toString(), relative, type, attributes.isRegularFile() ? attributes.size() : 0,
        attributes.lastModifiedTime().toInstant(),
        attributes.isRegularFile() ? SharedFolderContentPolicy.previewKind(target.getFileName().toString())
            : SharedFolderPreviewKind.NONE,
        SharedFolderObservedItemTokens.token(relative, attributes));
  }

  private SharedDirectoryEntry describeNative(String relative, NativeFileMetadata metadata) {
    String name = relative.substring(relative.lastIndexOf('/') + 1);
    SharedDirectoryEntryType type = metadata.directory()
        ? SharedDirectoryEntryType.DIRECTORY : SharedDirectoryEntryType.FILE;
    return new SharedDirectoryEntry(
        name, relative, type, metadata.regularFile() ? metadata.size() : 0, metadata.modifiedAt(),
        metadata.regularFile() ? SharedFolderContentPolicy.previewKind(name)
            : SharedFolderPreviewKind.NONE,
        nativeToken(relative, metadata));
  }

  private void requireNativeToken(
      String relative, NativeFileMetadata metadata, String suppliedToken) {
    if (!SharedFolderObservedItemTokens.matches(suppliedToken, nativeToken(relative, metadata))) {
      throw conflict();
    }
  }

  private String nativeToken(String relative, NativeFileMetadata metadata) {
    String identity = metadata.identity().volumeSerial() + ":"
        + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(metadata.identity().fileId());
    return SharedFolderObservedItemTokens.token(
        relative, identity, metadata.directory(), metadata.size(), metadata.modifiedAt());
  }

  private void moveCaseOnly(Path source, Path target, SharedFolderPathResolver resolver) throws IOException {
    Path temporary = source.getParent().resolve("__shared-folder-case-" + UUID.randomUUID());
    moveAtomically(source, temporary, false);
    resolver.recheckForMutation(temporary.getParent());
    moveAtomically(temporary, target, false);
  }

  protected void moveAtomically(Path source, Path target, boolean replace) throws IOException {
    try {
      if (replace) {
        Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      } else {
        // ATOMIC_MOVE may replace an existing target even without REPLACE_EXISTING on Windows.
        // The ordinary same-volume move preserves the explicit no-replacement contract.
        Files.move(source, target);
      }
    } catch (AtomicMoveNotSupportedException exception) {
      throw conflict();
    }
  }

  private void requireSameFileStore(Path first, Path second) throws IOException {
    if (!Files.getFileStore(first).equals(Files.getFileStore(second))) {
      throw conflict();
    }
  }

  private String parentPath(String path) {
    int separator = path == null ? -1 : path.lastIndexOf('/');
    return separator < 0 ? "" : path.substring(0, separator);
  }

  private String relativePath(String parent, String name) {
    return parent == null || parent.isEmpty() ? name : parent + "/" + name;
  }

  private ResponseStatusException conflict() {
    return new ResponseStatusException(HttpStatus.CONFLICT, "Shared-folder target already exists");
  }

  private ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Shared-folder item was not found");
  }

}
