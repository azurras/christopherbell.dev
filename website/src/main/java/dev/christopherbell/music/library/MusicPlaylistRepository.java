package dev.christopherbell.music.library;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface MusicPlaylistRepository extends MongoRepository<MusicPlaylist, String> {}
