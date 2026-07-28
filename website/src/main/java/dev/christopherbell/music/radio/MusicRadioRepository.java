package dev.christopherbell.music.radio;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface MusicRadioRepository extends MongoRepository<MusicRadioState, String> {}
