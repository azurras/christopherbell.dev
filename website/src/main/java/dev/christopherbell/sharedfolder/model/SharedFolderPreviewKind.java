package dev.christopherbell.sharedfolder.model;

/** Browser-safe ways the portal may render a shared-folder entry. */
public enum SharedFolderPreviewKind {
  /** The item must be downloaded instead of rendered inline. */
  NONE,
  /** The item is returned as escaped UTF-8 text in a JSON response. */
  TEXT,
  /** The item is a raster image that may be rendered inline. */
  IMAGE,
  /** The item is an allowlisted audio format that may be rendered inline. */
  AUDIO,
  /** The item is an allowlisted video format that may be rendered inline. */
  VIDEO,
  /** The item is a PDF returned with a restrictive sandbox policy. */
  PDF
}
