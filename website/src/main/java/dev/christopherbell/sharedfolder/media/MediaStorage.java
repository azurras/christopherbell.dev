package dev.christopherbell.sharedfolder.media;

import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.sharedfolder.fs.SharedFolderPathResolver;
import dev.christopherbell.sharedfolder.fs.UnsafeSharedPathException;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Fixed private media directories and atomic worker handoff files beneath the system root. */
@Component
public class MediaStorage {
  static final String JOBS = "shared-folder-media-jobs";
  static final String PARTIAL = "shared-folder-media-partial";
  static final String READY = "shared-folder-media-cache";
  static final String STATUS = "shared-folder-media-status";
  static final String CANCEL = "shared-folder-media-cancel";
  private static final String SAFE_KEY = "[a-f0-9]{64}|[A-Za-z0-9_-]{1,100}";

  private final Path systemRoot;
  private final boolean enabled;
  private final ObjectMapper mapper;
  private final Map<String, AtomicInteger> readers = new ConcurrentHashMap<>();
  private volatile Long usableSpaceForTest;

  public MediaStorage(SharedFolderProperties properties) {
    this(properties.systemRoot(), properties.enabled(), new ObjectMapper());
  }

  MediaStorage(Path systemRoot, ObjectMapper mapper) {
    this(systemRoot, true, mapper);
  }

  MediaStorage(Path systemRoot, boolean enabled, ObjectMapper mapper) {
    this.systemRoot = Objects.requireNonNull(systemRoot).toAbsolutePath().normalize();
    this.enabled = enabled;
    this.mapper = Objects.requireNonNull(mapper);
  }

  @PostConstruct
  public void initialize() throws IOException {
    if (!enabled) return;
    requireOrdinaryDirectory(systemRoot);
    for (String name : new String[] {JOBS, PARTIAL, READY, STATUS, CANCEL}) {
      Path directory = systemRoot.resolve(name);
      if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory);
      requireOrdinaryDirectory(directory);
    }
  }

  public long usableSpace() throws IOException {
    Long override = usableSpaceForTest;
    return override == null ? Files.getFileStore(directory(READY)).getUsableSpace() : override;
  }

  public void writeDescriptor(MediaWorkerJobDescriptor descriptor) throws IOException {
    requireKey(descriptor.jobId());
    byte[] bytes = mapper.writeValueAsBytes(descriptor.asMap());
    atomicWrite(directory(JOBS), descriptor.jobId() + ".json", bytes);
  }

  public String readDescriptor(String id) throws IOException {
    requireKey(id);
    return Files.readString(leaf(JOBS, id + ".json"));
  }

  public boolean readyExists(MediaJob job) {
    try {
      Path path = readyPath(job);
      return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path);
    } catch (IOException exception) {
      return false;
    }
  }

  public void touchReady(MediaJob job) {
    try {
      Path path = readyPath(job);
      requireOrdinaryFile(path);
      Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.from(java.time.Instant.now()));
    } catch (IOException ignored) {
      // A missing cache entry is handled by the normal ready-file check.
    }
  }

  public MediaStoredFile readyFile(MediaJob job) throws IOException {
    Path path = readyPath(job);
    requireOrdinaryFile(path);
    String cacheKey = job.getCacheKey();
    readers.computeIfAbsent(cacheKey, ignored -> new AtomicInteger()).incrementAndGet();
    java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
    Runnable release = () -> {
      if (!released.compareAndSet(false, true)) return;
      AtomicInteger count = readers.get(cacheKey);
      if (count != null && count.decrementAndGet() <= 0) readers.remove(cacheKey, count);
    };
    return new MediaStoredFile(
        new LeasedFileResource(path, release), Files.size(path), release);
  }

  public Path partialPath(MediaJob job) throws IOException {
    requireKey(job.getId());
    return leaf(PARTIAL, job.getId() + "." + job.getProfile().extension() + ".part");
  }

  public Path readyPath(MediaJob job) throws IOException {
    requireKey(job.getCacheKey());
    return leaf(READY, job.getCacheKey() + "." + job.getProfile().extension());
  }

  public Path statusPath(MediaJob job) throws IOException {
    requireKey(job.getId());
    return leaf(STATUS, job.getId() + ".json");
  }

  public Path cancellationPath(MediaJob job) throws IOException {
    requireKey(job.getId());
    return leaf(CANCEL, job.getId() + ".cancel");
  }

  /** Atomically publishes a fixed empty cancellation marker for the isolated worker. */
  public void requestCancellation(MediaJob job) throws IOException {
    atomicWrite(directory(CANCEL), job.getId() + ".cancel", new byte[0]);
  }

  /** Reads one bounded worker-owned status document; malformed or mismatched files are ignored. */
  public java.util.Optional<MediaWorkerStatus> readStatus(MediaJob job, long maxOutputBytes) {
    try {
      Path path = statusPath(job);
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)
          || Files.size(path) > 16 * 1024) return java.util.Optional.empty();
      var root = mapper.readTree(Files.readString(path));
      if (root.path("schemaVersion").asInt(-1) != 1
          || !job.getId().equals(root.path("jobId").asText(""))) {
        return java.util.Optional.empty();
      }
      MediaJobStatus status = MediaJobStatus.valueOf(root.path("status").asText(""));
      long outputBytes = root.path("outputBytes").asLong(-1);
      if (outputBytes < 0 || outputBytes > maxOutputBytes) return java.util.Optional.empty();
      String category = root.path("failureCategory").isMissingNode()
          || root.path("failureCategory").isNull() ? null
          : root.path("failureCategory").asText();
      if (category != null && !category.matches("[a-z_]{1,40}")) {
        return java.util.Optional.empty();
      }
      return java.util.Optional.of(new MediaWorkerStatus(status, outputBytes, category));
    } catch (IOException | IllegalArgumentException exception) {
      return java.util.Optional.empty();
    }
  }

  /** LRU eviction that excludes active jobs and files with an open website streaming lease. */
  public void evictToLimit(long limitBytes, Set<String> protectedCacheKeys) throws IOException {
    if (limitBytes < 1) return;
    Map<Path, BasicFileAttributes> files = new LinkedHashMap<>();
    try (var stream = Files.list(directory(READY))) {
      stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
          .forEach(path -> {
            try { files.put(path, Files.readAttributes(path, BasicFileAttributes.class)); }
            catch (IOException ignored) { }
          });
    }
    long total = files.values().stream().mapToLong(BasicFileAttributes::size).sum();
    if (total <= limitBytes) return;
    var oldest = files.entrySet().stream()
        .sorted(Comparator.comparing(entry -> entry.getValue().lastAccessTime().toInstant()))
        .toList();
    for (var entry : oldest) {
      if (total <= limitBytes) break;
      String cacheKey = entry.getKey().getFileName().toString().split("\\.", 2)[0];
      if (protectedCacheKeys.contains(cacheKey)
          || readers.getOrDefault(cacheKey, new AtomicInteger()).get() > 0) continue;
      long size = entry.getValue().size();
      Files.deleteIfExists(entry.getKey());
      total -= size;
    }
  }

  void writeReadyForTest(MediaJob job, byte[] bytes) throws IOException {
    atomicWrite(directory(READY), readyPath(job).getFileName().toString(), bytes);
  }

  void writeStatusForTest(
      MediaJob job, MediaJobStatus status, long outputBytes, String failureCategory)
      throws IOException {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("schemaVersion", 1);
    values.put("jobId", job.getId());
    values.put("status", status.name());
    values.put("outputBytes", outputBytes);
    values.put("failureCategory", failureCategory);
    atomicWrite(directory(STATUS), job.getId() + ".json", mapper.writeValueAsBytes(values));
  }

  void setUsableSpaceForTest(long bytes) { usableSpaceForTest = bytes; }

  private void atomicWrite(Path directory, String name, byte[] bytes) throws IOException {
    Path target = safeLeaf(directory, name);
    Path temporary = safeLeaf(directory, name + ".tmp");
    Files.write(temporary, bytes);
    try {
      Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private Path leaf(String directory, String name) throws IOException {
    return safeLeaf(directory(directory), name);
  }

  private Path directory(String name) throws IOException {
    Path path = systemRoot.resolve(name).normalize();
    requireOrdinaryDirectory(path);
    return path;
  }

  private Path safeLeaf(Path directory, String name) throws IOException {
    if (name == null || name.isBlank() || name.contains("/") || name.contains("\\")) {
      throw new IOException("Unsafe media storage name");
    }
    Path leaf = directory.resolve(name).normalize();
    if (!leaf.getParent().equals(directory)) throw new IOException("Media storage path escaped");
    return leaf;
  }

  private void requireKey(String key) throws IOException {
    if (key == null || !key.matches(SAFE_KEY)) throw new IOException("Invalid media identifier");
  }

  private void requireOrdinaryDirectory(Path path) throws IOException {
    try {
      SharedFolderPathResolver resolver = new SharedFolderPathResolver(systemRoot);
      Path verified = path.equals(systemRoot)
          ? systemRoot : resolver.existing(systemRoot.relativize(path).toString().replace('\\', '/'));
      if (!resolver.readHandle(verified).attributes().isDirectory()) {
        throw new IOException("Media storage directory is unavailable");
      }
    } catch (UnsafeSharedPathException exception) {
      throw new IOException("Media storage directory is unavailable", exception);
    }
  }

  private void requireOrdinaryFile(Path path) throws IOException {
    try {
      SharedFolderPathResolver resolver = new SharedFolderPathResolver(systemRoot);
      Path verified = resolver.existing(systemRoot.relativize(path).toString().replace('\\', '/'));
      if (!resolver.readHandle(verified).attributes().isRegularFile()) {
        throw new IOException("Media output is unavailable");
      }
    } catch (UnsafeSharedPathException exception) {
      throw new IOException("Media output is unavailable", exception);
    }
  }

  /** Open ready file plus an eviction lease that callers must close. */
  public record MediaStoredFile(Resource resource, long length, Runnable release) {
    public void close() { release.run(); }
  }

  private static final class LeasedFileResource extends AbstractResource {
    private final Path path;
    private final Runnable release;

    private LeasedFileResource(Path path, Runnable release) {
      this.path = path;
      this.release = release;
    }

    @Override
    public InputStream getInputStream() throws IOException {
      try {
        return new FilterInputStream(Files.newInputStream(path)) {
          @Override public void close() throws IOException {
            try { super.close(); } finally { release.run(); }
          }
        };
      } catch (IOException failure) {
        release.run();
        throw failure;
      }
    }

    @Override public long contentLength() throws IOException { return Files.size(path); }
    @Override public String getFilename() { return path.getFileName().toString(); }
    @Override public String getDescription() { return "shared-folder cached media resource"; }
  }

  /** Strict worker progress fields accepted by the website. */
  public record MediaWorkerStatus(
      MediaJobStatus status, long outputBytes, String failureCategory) {}
}
