package dev.christopherbell.music.radio;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface MusicQueueStateRepository extends MongoRepository<MusicQueueState, String> {}
