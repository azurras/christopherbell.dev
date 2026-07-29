package dev.christopherbell.view;

import org.springframework.ui.Model;

/** Shared model contract for pages that crawlers must not index or follow. */
public final class ViewIndexingPolicy {
  public static final String ROBOTS_ATTRIBUTE = "robotsContent";
  public static final String NO_INDEX = "noindex,nofollow";

  private ViewIndexingPolicy() {}

  /** Marks the rendered view as private or otherwise unsuitable for indexing. */
  public static void noIndex(Model model) {
    model.addAttribute(ROBOTS_ATTRIBUTE, NO_INDEX);
  }
}
