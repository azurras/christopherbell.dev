package dev.christopherbell.post.preview;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PostLinkPreviewCacheRepository
    extends MongoRepository<PostLinkPreviewCacheEntry, String> {}
