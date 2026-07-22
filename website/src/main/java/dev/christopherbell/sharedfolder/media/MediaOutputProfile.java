package dev.christopherbell.sharedfolder.media;

import org.springframework.http.MediaType;

/** Closed worker profiles; requests cannot supply executable names or command arguments. */
public enum MediaOutputProfile {
  VIDEO_MP4("mp4", MediaType.valueOf("video/mp4")),
  AUDIO_M4A("m4a", MediaType.valueOf("audio/mp4"));

  private final String extension;
  private final MediaType mediaType;

  MediaOutputProfile(String extension, MediaType mediaType) {
    this.extension = extension;
    this.mediaType = mediaType;
  }

  public String extension() { return extension; }

  public MediaType mediaType() { return mediaType; }
}
