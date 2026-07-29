package dev.christopherbell.post.feed;

import dev.christopherbell.post.model.PostDetail;
import java.util.List;

/** One stable, cursor-paginated page of post details. */
public record PostDetailPage(List<PostDetail> items, String nextCursor) {}
