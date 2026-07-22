package dev.christopherbell.sharedfolder.media;

/** Safe browser-facing media state; local paths and worker details are deliberately absent. */
public record MediaPlaybackDescriptor(
    MediaPlaybackMode mode,
    String jobId,
    MediaJobStatus status,
    MediaOutputProfile profile) {

  static MediaPlaybackDescriptor directProbe() {
    return new MediaPlaybackDescriptor(MediaPlaybackMode.DIRECT_PROBE, null, null, null);
  }

  static MediaPlaybackDescriptor from(MediaJob job) {
    MediaPlaybackMode mode = job.getStatus() == MediaJobStatus.READY
        ? MediaPlaybackMode.READY : MediaPlaybackMode.TRANSCODING;
    return new MediaPlaybackDescriptor(mode, job.getId(), job.getStatus(), job.getProfile());
  }
}
