package dev.christopherbell.sharedfolder.media;

import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.sharedfolder.fs.PortableSharedFolderPrivateBoundary;
import dev.christopherbell.sharedfolder.fs.PortableSharedFolderPrivateBoundary.FileAccess;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Fixed private media storage whose website operations retain a verified file handle. */
@Component
public class MediaStorage {
  static final String JOBS = "shared-folder-media-jobs";
  static final String PARTIAL = "shared-folder-media-partial";
  static final String READY = "shared-folder-media-cache";
  static final String STATUS = "shared-folder-media-status";
  static final String CANCEL = "shared-folder-media-cancel";
  private static final int STATUS_LIMIT_BYTES = 16 * 1024;
  private static final String SAFE_KEY = "[a-f0-9]{64}|[A-Za-z0-9_-]{1,100}";

  private final Path systemRoot;
  private final boolean enabled;
  private final ObjectMapper mapper;
  private final PortableSharedFolderPrivateBoundary boundary;
  private final Map<String, AtomicInteger> readers = new ConcurrentHashMap<>();
  private final Object readyLeaseLock = new Object();
  private volatile Long usableSpaceForTest;

  @Autowired
  public MediaStorage(SharedFolderProperties properties) {
    this(properties.systemRoot(), properties.enabled(), new ObjectMapper());
  }

  MediaStorage(Path systemRoot, ObjectMapper mapper) {
    this(systemRoot, true, mapper);
  }

  MediaStorage(Path systemRoot, boolean enabled, ObjectMapper mapper) {
    this(systemRoot, enabled, mapper,
        new PortableSharedFolderPrivateBoundary(
            Objects.requireNonNull(systemRoot).toAbsolutePath().normalize()));
  }

  MediaStorage(
      Path systemRoot,
      boolean enabled,
      ObjectMapper mapper,
      PortableSharedFolderPrivateBoundary boundary) {
    this.systemRoot = Objects.requireNonNull(systemRoot).toAbsolutePath().normalize();
    this.enabled = enabled;
    this.mapper = Objects.requireNonNull(mapper);
    this.boundary = Objects.requireNonNull(boundary);
  }

  @PostConstruct
  public void initialize() throws IOException {
    if (!enabled) return;
    for (String name : new String[] {JOBS, PARTIAL, READY, STATUS, CANCEL}) {
      boundary.usableSpace(name);
    }
  }

  public long usableSpace() throws IOException {
    Long override = usableSpaceForTest;
    return override == null ? boundary.usableSpace(READY) : override;
  }

  /** Publishes complete descriptor bytes before a separate zero-byte ready marker becomes visible. */
  public void writeDescriptor(MediaWorkerJobDescriptor descriptor) throws IOException {
    requireKey(descriptor.jobId());
    byte[] expected = mapper.writeValueAsBytes(descriptor.asMap());
    try {
      writeNew(JOBS, descriptor.jobId() + ".json", expected);
    } catch (FileAlreadyExistsException existing) {
      final byte[] actual;
      try {
        actual = readBounded(JOBS, descriptor.jobId() + ".json", 64 * 1024);
      } catch (MediaDocumentSizeException oversized) {
        throw new MediaDescriptorProtocolException(
            "Published media descriptor exceeds its protocol limit", oversized);
      }
      try {
        if (!mapper.readTree(actual).equals(mapper.valueToTree(descriptor.asMap()))) {
          throw new MediaDescriptorProtocolException(
              "Published media descriptor does not match its durable job");
        }
      } catch (JacksonException | IllegalArgumentException invalid) {
        throw new MediaDescriptorProtocolException(
            "Published media descriptor is incomplete or malformed", invalid);
      }
    }
    try {
      writeNew(JOBS, descriptor.jobId() + ".ready", new byte[0]);
    } catch (FileAlreadyExistsException ignored) {
      // The matching durable descriptor was already published before an interrupted save/restart.
    }
  }

  public String readDescriptor(String id) throws IOException {
    requireKey(id);
    return new String(readBounded(JOBS, id + ".json", 64 * 1024), java.nio.charset.StandardCharsets.UTF_8);
  }

  public boolean readyMatches(MediaJob job, long expectedBytes, long maxOutputBytes)
      throws IOException {
    OptionalLong actual = readyLength(job, maxOutputBytes);
    return actual.isPresent() && actual.getAsLong() == expectedBytes;
  }

  /** Returns empty only when the completed output is absent; all other failures remain visible. */
  public OptionalLong readyLength(MediaJob job, long maxOutputBytes) throws IOException {
    try {
      long size = boundary.metadata(READY, readyKey(job)).attributes().size();
      if (size > maxOutputBytes) throw new MediaOutputLimitException();
      return OptionalLong.of(size);
    } catch (NoSuchFileException exception) {
      return OptionalLong.empty();
    }
  }

  public long partialLength(MediaJob job, long maxOutputBytes) throws IOException {
    try {
      long size = boundary.metadata(PARTIAL, partialKey(job)).attributes().size();
      if (size > maxOutputBytes) throw new MediaOutputLimitException();
      return size;
    } catch (NoSuchFileException exception) {
      return 0;
    }
  }

  /** Copies an exact held-handle region and protects ready output from concurrent eviction. */
  public long copy(
      MediaJob job, boolean ready, long position, long length, OutputStream output,
      long maxOutputBytes) throws IOException {
    if (position < 0 || length < 0) throw new IllegalArgumentException("Invalid media copy range");
    String directory = ready ? READY : PARTIAL;
    String key = ready ? readyKey(job) : partialKey(job);
    try (ReadyLease ignored = ready ? acquireReadyLease(job) : null) {
      return boundary.operateOnRegularFile(directory, key, FileAccess.READ, channel -> {
        long size = channel.size();
        if (size > maxOutputBytes) throw new MediaOutputLimitException();
        if (position > size) throw new IOException("Media output changed during streaming");
        long remaining = Math.min(length, size - position);
        channel.position(position);
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        long copied = 0;
        while (copied < remaining) {
          buffer.clear();
          buffer.limit((int) Math.min(buffer.capacity(), remaining - copied));
          int read = channel.read(buffer);
          if (read <= 0) break;
          output.write(buffer.array(), 0, read);
          copied += read;
        }
        return copied;
      });
    }
  }

  public boolean deleteReady(MediaJob job) throws IOException {
    synchronized (readyLeaseLock) {
      if (readerCount(job.getCacheKey()) > 0) return false;
      return boundary.deleteIfExists(READY, readyKey(job));
    }
  }

  public boolean isBeingRead(String cacheKey) {
    synchronized (readyLeaseLock) {
      return readerCount(cacheKey) > 0;
    }
  }

  /** Prevents cache eviction from range selection until its asynchronous response body completes. */
  public ReadyLease acquireReadyLease(MediaJob job) throws IOException {
    requireKey(job.getCacheKey());
    synchronized (readyLeaseLock) {
      readers.computeIfAbsent(job.getCacheKey(), ignored -> new AtomicInteger()).incrementAndGet();
      return new ReadyLease(this, job.getCacheKey());
    }
  }

  public Path partialPath(MediaJob job) throws IOException {
    return workerPath(PARTIAL, partialKey(job));
  }

  public Path readyPath(MediaJob job) throws IOException {
    return workerPath(READY, readyKey(job));
  }

  public Path statusPath(MediaJob job) throws IOException {
    requireKey(job.getId());
    return workerPath(STATUS, job.getId() + ".json");
  }

  public Path cancellationPath(MediaJob job) throws IOException {
    requireKey(job.getId());
    return workerPath(CANCEL, job.getId() + ".cancel");
  }

  /** Idempotently publishes a fixed empty cancellation marker for the isolated worker. */
  public void requestCancellation(MediaJob job) throws IOException {
    try {
      writeNew(CANCEL, job.getId() + ".cancel", new byte[0]);
    } catch (FileAlreadyExistsException ignored) {
      // A prior cancellation request is already authoritative.
    }
  }

  /** Reads one bounded worker-owned status document; malformed or mismatched files are ignored. */
  public Optional<MediaWorkerStatus> readStatus(MediaJob job, long maxOutputBytes)
      throws IOException {
    byte[] bytes;
    try {
      bytes = readBounded(STATUS, job.getId() + ".json", STATUS_LIMIT_BYTES);
    } catch (NoSuchFileException exception) {
      return Optional.empty();
    } catch (MediaDocumentSizeException oversized) {
      throw new MediaWorkerProtocolException(
          "Media worker status exceeds its protocol limit", oversized);
    }
    final tools.jackson.databind.JsonNode root;
    try {
      root = mapper.readTree(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    } catch (JacksonException | IllegalArgumentException invalid) {
      throw new MediaWorkerProtocolException("Invalid media worker status protocol", invalid);
    }
    if (root.path("schemaVersion").asInt(-1) != 1
        || !job.getId().equals(root.path("jobId").asText(""))) {
      throw new MediaWorkerProtocolException("Invalid media worker status protocol");
    }
    final MediaJobStatus status;
    try {
      status = MediaJobStatus.valueOf(root.path("status").asText(""));
    } catch (IllegalArgumentException invalid) {
      throw new MediaWorkerProtocolException("Invalid media worker status protocol", invalid);
    }
    long outputBytes = root.path("outputBytes").asLong(-1);
    if (outputBytes < 0) {
      throw new MediaWorkerProtocolException("Invalid media worker status protocol");
    }
    if (outputBytes > maxOutputBytes) throw new MediaOutputLimitException();
    String category = root.path("failureCategory").isMissingNode()
        || root.path("failureCategory").isNull() ? null
        : root.path("failureCategory").asText();
    if (category != null && !category.matches("[a-z_]{1,40}")) {
      throw new MediaWorkerProtocolException("Invalid media worker status protocol");
    }
    return Optional.of(new MediaWorkerStatus(status, outputBytes, category));
  }

  void writeReadyForTest(MediaJob job, byte[] bytes) throws IOException {
    job.setOutputBytes(bytes.length);
    writeReplacingForTest(READY, readyKey(job), bytes);
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
    writeReplacingForTest(STATUS, job.getId() + ".json", mapper.writeValueAsBytes(values));
  }

  void setUsableSpaceForTest(long bytes) { usableSpaceForTest = bytes; }

  private void writeNew(String directory, String key, byte[] bytes) throws IOException {
    boundary.operateOnRegularFile(directory, key, FileAccess.CREATE_NEW, channel -> {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) channel.write(buffer);
      channel.force(true);
      return null;
    });
  }

  private void writeReplacingForTest(String directory, String key, byte[] bytes) throws IOException {
    boundary.deleteIfExists(directory, key);
    writeNew(directory, key, bytes);
  }

  private byte[] readBounded(String directory, String key, int limit) throws IOException {
    return boundary.operateOnRegularFile(directory, key, FileAccess.READ, channel -> {
      if (channel.size() > limit) throw new MediaDocumentSizeException();
      ByteArrayOutputStream output = new ByteArrayOutputStream((int) channel.size());
      ByteBuffer buffer = ByteBuffer.allocate(4096);
      while (channel.read(buffer) > 0) {
        buffer.flip();
        output.write(buffer.array(), 0, buffer.remaining());
        buffer.clear();
      }
      return output.toByteArray();
    });
  }

  private Path workerPath(String directory, String key) throws IOException {
    if (key == null || key.isBlank() || key.contains("/") || key.contains("\\")) {
      throw new IOException("Unsafe media storage name");
    }
    boundary.usableSpace(directory);
    Path parent = systemRoot.resolve(directory).normalize();
    Path leaf = parent.resolve(key).normalize();
    if (!leaf.getParent().equals(parent)) throw new IOException("Media storage path escaped");
    return leaf;
  }

  private String readyKey(MediaJob job) throws IOException {
    requireKey(job.getCacheKey());
    return job.getCacheKey() + "." + job.getProfile().extension();
  }

  private String partialKey(MediaJob job) throws IOException {
    requireKey(job.getId());
    return job.getId() + "." + job.getProfile().extension() + ".part";
  }

  private void requireKey(String key) throws IOException {
    if (key == null || !key.matches(SAFE_KEY)) throw new IOException("Invalid media identifier");
  }

  private void releaseReader(String cacheKey) {
    synchronized (readyLeaseLock) {
      AtomicInteger count = readers.get(cacheKey);
      if (count != null && count.decrementAndGet() <= 0) readers.remove(cacheKey, count);
    }
  }

  private int readerCount(String cacheKey) {
    AtomicInteger count = readers.get(cacheKey);
    return count == null ? 0 : count.get();
  }

  /** Strict worker progress fields accepted by the website. */
  public record MediaWorkerStatus(
      MediaJobStatus status, long outputBytes, String failureCategory) {}

  /** Idempotent lease protecting one cache key from eviction. */
  public static final class ReadyLease implements AutoCloseable {
    private final MediaStorage storage;
    private final String cacheKey;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ReadyLease(MediaStorage storage, String cacheKey) {
      this.storage = storage;
      this.cacheKey = cacheKey;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) storage.releaseReader(cacheKey);
    }
  }

  /** Worker output exceeded the fixed descriptor limit and must become a terminal job failure. */
  public static final class MediaOutputLimitException extends IOException {
    private MediaOutputLimitException() {
      super("Media output exceeded its limit");
    }
  }

  /** Existing durable descriptor bytes conflict with the website-owned publication protocol. */
  public static final class MediaDescriptorProtocolException extends IOException {
    private MediaDescriptorProtocolException(String message) { super(message); }

    private MediaDescriptorProtocolException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Worker status bytes exist but do not satisfy the fixed website protocol. */
  public static final class MediaWorkerProtocolException extends IOException {
    private MediaWorkerProtocolException(String message) { super(message); }

    private MediaWorkerProtocolException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private static final class MediaDocumentSizeException extends IOException {
    private MediaDocumentSizeException() { super("Private media document is too large"); }
  }
}
