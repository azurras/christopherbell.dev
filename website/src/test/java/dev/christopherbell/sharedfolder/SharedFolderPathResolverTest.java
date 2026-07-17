package dev.christopherbell.sharedfolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.sharedfolder.fs.SharedFolderPathResolver;
import dev.christopherbell.sharedfolder.fs.UnsafeSharedPathException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
