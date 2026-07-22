package dev.christopherbell.sharedfolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.sharedfolder.fs.NioSharedFolderFileSystemBoundary;
import dev.christopherbell.sharedfolder.fs.SharedFolderFileSystemBoundary;
import dev.christopherbell.sharedfolder.fs.SharedFolderPathResolver;
import dev.christopherbell.sharedfolder.fs.UnsafeSharedPathException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class SharedFolderPathResolverTest {
  @TempDir Path temp;

  @Test
  void resolvesOnlyOrdinaryExistingDescendants() throws Exception {
    Path root = Files.createDirectory(temp.resolve("shared"));
    Files.createDirectories(root.resolve("music/live"));
    var resolver = new SharedFolderPathResolver(root);

    assertThat(resolver.existing("")).isEqualTo(root);
    assertThat(resolver.existing("music/live")).isEqualTo(root.resolve("music/live"));
  }

  @Test
  void acceptsAnOrdinaryRootReachedThroughAnAliasedAncestor() throws Exception {
    Path root = Files.createDirectory(temp.resolve("shared"));
    var resolver = new SharedFolderPathResolver(root, new AncestorAliasBoundary(root));

    assertThat(resolver.existing("")).isEqualTo(root);
  }

  @ParameterizedTest(name = "rejects unsafe Windows path: {0}")
  @MethodSource("unsafePaths")
  void rejectsUnsafeWindowsPathForms(String path) throws Exception {
    Path root = Files.createDirectory(temp.resolve("shared"));
    var resolver = new SharedFolderPathResolver(root);

    assertThatThrownBy(() -> resolver.existing(path))
        .isInstanceOf(UnsafeSharedPathException.class);
  }

  static Stream<String> unsafePaths() {
    return Stream.of(
        ".",
        "..",
        "../secret",
        "music/../secret",
        "/absolute",
        "\\absolute",
        "//server/share",
        "\\\\server\\share",
        "C:\\Windows",
        "C:relative",
        "file.txt:stream",
        "CON",
        "con.txt",
        "PrN",
        "AUX.log",
        "nul",
        "COM1",
        "com9.txt",
        "LPT1",
        "lpt9.txt",
        "COM" + '\u00b9',
        "lpt" + '\u00b2' + ".txt",
        "LPT" + '\u00b3',
        "name.",
        "name ",
        "music//live",
        "music/",
        "music/%2f/secret",
        "music/%2F/secret",
        "music/%5c/secret",
        "music/%5C/secret",
        "control\u0001name",
        "delete\u007fname",
        "c1" + '\u0085' + "name",
        "c1" + '\u009f' + "name",
        "bad\u0000name");
  }

  @Test
  void rejectsConfiguredAbsoluteRootAsRequestInput() throws Exception {
    Path root = Files.createDirectory(temp.resolve("shared"));
    var resolver = new SharedFolderPathResolver(root);

    assertThatThrownBy(() -> resolver.existing(root.toString()))
        .isInstanceOf(UnsafeSharedPathException.class);
  }

  @Test
  void newChildRequiresExistingSafeParentAndOneSafeWindowsName() throws Exception {
    Path root = Files.createDirectory(temp.resolve("shared"));
    Files.createDirectory(root.resolve("music"));
    var resolver = new SharedFolderPathResolver(root);

    assertThat(resolver.newChild("music", "live"))
        .isEqualTo(root.resolve("music/live"));
    assertThatThrownBy(() -> resolver.newChild("missing", "live"))
        .isInstanceOf(UnsafeSharedPathException.class);
    assertThatThrownBy(() -> resolver.newChild("music", "nested/live"))
        .isInstanceOf(UnsafeSharedPathException.class);
    assertThatThrownBy(() -> resolver.newChild("music", "CON.txt"))
        .isInstanceOf(UnsafeSharedPathException.class);
  }

  @Test
  void existingRejectsMissingLeafAndMissingAncestors() throws Exception {
    Path root = Files.createDirectory(temp.resolve("shared"));
    var resolver = new SharedFolderPathResolver(root);

    assertThatThrownBy(() -> resolver.existing("missing"))
        .isInstanceOf(UnsafeSharedPathException.class);
    assertThatThrownBy(() -> resolver.existing("missing/child"))
        .isInstanceOf(UnsafeSharedPathException.class);
  }

  @Test
  void rejectsAnyLinkOrJunctionInTheChain() throws Exception {
    Path root = Files.createDirectory(temp.resolve("shared"));
    Path outside = Files.createDirectories(temp.resolve("outside/nested"));
    Path linked = root.resolve("linked");
    assumeLinkOrJunctionCreated(linked, outside.getParent());
    var resolver = new SharedFolderPathResolver(root);

    assertThatThrownBy(() -> resolver.existing("linked/nested"))
        .isInstanceOf(UnsafeSharedPathException.class);
  }

  @Test
  void rejectsConfiguredRootThatIsALinkOrJunction() throws Exception {
    Path target = Files.createDirectory(temp.resolve("shared-target"));
    Path rootLink = temp.resolve("shared-link");
    assumeLinkOrJunctionCreated(rootLink, target);

    assertThatThrownBy(() -> new SharedFolderPathResolver(rootLink))
        .isInstanceOf(UnsafeSharedPathException.class);
  }

  @Test
  void rejectsConfiguredRootMarkedAsFilesystemMount() throws Exception {
    Path root = Files.createDirectory(temp.resolve("shared"));

    assertThatThrownBy(() -> new SharedFolderPathResolver(root, new MountBoundary(root)))
        .isInstanceOf(UnsafeSharedPathException.class);
  }

  @Test
  void rejectsRootWhenProductionMountMetadataIsUnavailable() throws Exception {
    Path root = Files.createDirectory(temp.resolve("shared"));
    var boundary = new NioSharedFolderFileSystemBoundary(
        canonicalPath -> {
          throw new IOException("mount metadata is unavailable");
        });

    assertThatThrownBy(() -> new SharedFolderPathResolver(root, boundary))
        .isInstanceOf(UnsafeSharedPathException.class);
  }

  @Test
  void rejectsFilesystemMountInExistingAncestor() throws Exception {
    Path root = Files.createDirectory(temp.resolve("shared"));
    Files.createDirectories(root.resolve("music/live"));
    var resolver = new SharedFolderPathResolver(root, new MountBoundary(root.resolve("music")));

    assertThatThrownBy(() -> resolver.existing("music/live"))
        .isInstanceOf(UnsafeSharedPathException.class);
  }

  @Test
  void rejectsExistingAncestorThatCrossesTheRootFileStore() throws Exception {
    Path root = Files.createDirectory(temp.resolve("shared"));
    Files.createDirectories(root.resolve("music/live"));
    var resolver = new SharedFolderPathResolver(
        root, new DifferentFileStoreBoundary(root.resolve("music")));

    assertThatThrownBy(() -> resolver.existing("music/live"))
        .isInstanceOf(UnsafeSharedPathException.class);
  }

  @Test
  void rejectsExistingAncestorWhoseCanonicalIdentityEscapesTheConfiguredRoot() throws Exception {
    Path root = Files.createDirectory(temp.resolve("shared"));
    Files.createDirectories(root.resolve("music/live"));
    Path outside = Files.createDirectory(temp.resolve("outside"));
    var resolver = new SharedFolderPathResolver(
        root, new CanonicalEscapeBoundary(root.resolve("music"), outside));

    assertThatThrownBy(() -> resolver.existing("music/live"))
        .isInstanceOf(UnsafeSharedPathException.class);
  }

  @Test
  void mutationRecheckRejectsAncestorReplacedByLinkOrJunction() throws Exception {
    Path root = Files.createDirectory(temp.resolve("shared"));
    Path parent = Files.createDirectories(root.resolve("music"));
    Files.writeString(parent.resolve("song.txt"), "safe");
    Path outside = Files.createDirectories(temp.resolve("outside"));
    Files.writeString(outside.resolve("song.txt"), "outside");
    var resolver = new SharedFolderPathResolver(root);
    Path resolved = resolver.existing("music/song.txt");

    Files.move(parent, temp.resolve("original-music"));
    assumeLinkOrJunctionCreated(parent, outside);

    assertThatThrownBy(() -> resolver.recheckForMutation(resolved))
        .isInstanceOf(UnsafeSharedPathException.class);
  }

  @Test
  void readHandleRejectsDirectorySubstitutionImmediatelyBeforeListingOpen() throws Exception {
    Path root = Files.createDirectory(temp.resolve("shared"));
    Path directory = Files.createDirectory(root.resolve("music"));
    var resolver = new SharedFolderPathResolver(root);
    var handle = resolver.readHandle(resolver.existing("music"));

    Files.move(directory, temp.resolve("original-music"));
    Files.createDirectory(directory);

    assertThatThrownBy(handle::openDirectory)
        .isInstanceOf(UnsafeSharedPathException.class);
  }

  @Test
  void readHandleRejectsFileSubstitutionImmediatelyBeforeTextPreviewOpen() throws Exception {
    Path root = Files.createDirectory(temp.resolve("shared"));
    Path notes = Files.writeString(root.resolve("notes.txt"), "original");
    var resolver = new SharedFolderPathResolver(root);
    var handle = resolver.readHandle(resolver.existing("notes.txt"));

    Files.move(notes, temp.resolve("original-notes.txt"));
    Files.writeString(notes, "replacement");

    assertThatThrownBy(handle::openFile)
        .isInstanceOf(UnsafeSharedPathException.class);
  }

  @Test
  void readHandleMapsAnUnsupportedNoFollowOpenToTheFailClosedPathResult() throws Exception {
    Path root = Files.createDirectory(temp.resolve("shared"));
    Files.writeString(root.resolve("notes.txt"), "safe");
    var resolver = new SharedFolderPathResolver(root, new UnsupportedNoFollowBoundary());
    var handle = resolver.readHandle(resolver.existing("notes.txt"));

    assertThatThrownBy(handle::openFile)
        .isInstanceOf(UnsafeSharedPathException.class)
        .hasMessageNotContaining(root.toString());
  }

  private void assumeLinkOrJunctionCreated(Path link, Path target) throws Exception {
    try {
      Files.createSymbolicLink(link, target);
      return;
    } catch (IOException | UnsupportedOperationException | SecurityException exception) {
      // Windows junctions normally do not require the symbolic-link privilege.
    }

    if (System.getProperty("os.name", "").toLowerCase().contains("windows")) {
      Process process = new ProcessBuilder(
          "cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), target.toString())
          .redirectErrorStream(true)
          .start();
      String output = new String(process.getInputStream().readAllBytes());
      int exitCode = process.waitFor();
      Assumptions.assumeTrue(exitCode == 0, "junction unavailable: " + output);
      return;
    }

    Assumptions.abort("symbolic links are unavailable on this test host");
  }

  private static class DelegatingBoundary implements SharedFolderFileSystemBoundary {
    private final SharedFolderFileSystemBoundary delegate = new NioSharedFolderFileSystemBoundary();

    @Override
    public Path absoluteNormalized(Path path) {
      return delegate.absoluteNormalized(path);
    }

    @Override
    public boolean existsNoFollow(Path path) {
      return delegate.existsNoFollow(path);
    }

    @Override
    public BasicFileAttributes readAttributesNoFollow(Path path) throws IOException {
      return delegate.readAttributesNoFollow(path);
    }

    @Override
    public Path realPath(Path path) throws IOException {
      return delegate.realPath(path);
    }

    @Override
    public Path realPathNoFollow(Path path) throws IOException {
      return delegate.realPathNoFollow(path);
    }

    @Override
    public boolean sameFileStore(Path first, Path second) throws IOException {
      return delegate.sameFileStore(first, second);
    }

    @Override
    public boolean isMountPoint(Path path) throws IOException {
      return delegate.isMountPoint(path);
    }

    @Override
    public Object dosAttributesNoFollow(Path path) throws IOException {
      return delegate.dosAttributesNoFollow(path);
    }

    @Override
    public DirectoryStream<Path> openDirectory(Path path) throws IOException {
      return delegate.openDirectory(path);
    }

    @Override
    public InputStream openFileNoFollow(Path path) throws IOException {
      return delegate.openFileNoFollow(path);
    }
  }

  private static final class MountBoundary extends DelegatingBoundary {
    private final Path mountPoint;

    private MountBoundary(Path mountPoint) {
      this.mountPoint = mountPoint.toAbsolutePath().normalize();
    }

    @Override
    public boolean isMountPoint(Path path) throws IOException {
      return mountPoint.equals(path.toAbsolutePath().normalize()) || super.isMountPoint(path);
    }
  }

  private static final class AncestorAliasBoundary extends DelegatingBoundary {
    private final Path root;

    private AncestorAliasBoundary(Path root) {
      this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public Path realPathNoFollow(Path path) throws IOException {
      if (!root.equals(path.toAbsolutePath().normalize())) {
        return super.realPathNoFollow(path);
      }
      return super.realPath(path).resolveSibling("aliased-ancestor").resolve(root.getFileName());
    }
  }

  private static final class CanonicalEscapeBoundary extends DelegatingBoundary {
    private final Path escapedAncestor;
    private final Path outside;

    private CanonicalEscapeBoundary(Path escapedAncestor, Path outside) {
      this.escapedAncestor = escapedAncestor.toAbsolutePath().normalize();
      this.outside = outside.toAbsolutePath().normalize();
    }

    @Override
    public Path realPath(Path path) throws IOException {
      return escapedAncestor.equals(path.toAbsolutePath().normalize()) ? outside : super.realPath(path);
    }

    @Override
    public Path realPathNoFollow(Path path) throws IOException {
      return escapedAncestor.equals(path.toAbsolutePath().normalize())
          ? outside
          : super.realPathNoFollow(path);
    }
  }

  private static final class DifferentFileStoreBoundary extends DelegatingBoundary {
    private final Path crossedAncestor;

    private DifferentFileStoreBoundary(Path crossedAncestor) {
      this.crossedAncestor = crossedAncestor.toAbsolutePath().normalize();
    }

    @Override
    public boolean sameFileStore(Path first, Path second) throws IOException {
      return !crossedAncestor.equals(second.toAbsolutePath().normalize())
          && super.sameFileStore(first, second);
    }
  }

  private static final class UnsupportedNoFollowBoundary extends DelegatingBoundary {
    @Override
    public InputStream openFileNoFollow(Path path) {
      throw new UnsupportedOperationException("NOFOLLOW is unavailable");
    }
  }
}
