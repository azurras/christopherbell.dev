package dev.christopherbell.music.metadata;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;

/** Private checksum-bound backup and audit record for one applied metadata edit. */
public record MusicMetadataEdit(
    @Id String id,
    @Indexed String trackId,
    String sourcePath,
    String backupFileName,
    String backupSha256,
    String originalObservedToken,
    String replacementObservedToken,
    String originalAudioCodec,
    double originalDurationSeconds,
    String editedByAccountId,
    Instant createdAt,
    @Indexed Instant expiresAt,
    Status status,
    Instant undoneAt,
    @Version Long version) {

  public enum Status {
    PREPARED,
    APPLIED,
    UNDONE
  }

  public MusicMetadataEdit applied(String replacementToken) {
    return new MusicMetadataEdit(
        id, trackId, sourcePath, backupFileName, backupSha256, originalObservedToken,
        replacementToken, originalAudioCodec, originalDurationSeconds, editedByAccountId,
        createdAt, expiresAt, Status.APPLIED, null, version);
  }

  public MusicMetadataEdit undone(Instant now) {
    return new MusicMetadataEdit(
        id, trackId, sourcePath, backupFileName, backupSha256, originalObservedToken,
        replacementObservedToken, originalAudioCodec, originalDurationSeconds, editedByAccountId,
        createdAt, expiresAt, Status.UNDONE, now, version);
  }
}
