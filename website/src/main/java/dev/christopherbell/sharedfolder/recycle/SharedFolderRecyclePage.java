package dev.christopherbell.sharedfolder.recycle;

import java.util.List;

/** Internal bounded recycle page with authoritative continuation state. */
public record SharedFolderRecyclePage(
    List<SharedFolderRecycleItem> items,
    int page,
    boolean hasNext) {
  public SharedFolderRecyclePage {
    items = List.copyOf(items);
  }
}
