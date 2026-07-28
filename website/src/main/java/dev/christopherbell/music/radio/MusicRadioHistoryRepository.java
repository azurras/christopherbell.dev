package dev.christopherbell.music.radio;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface MusicRadioHistoryRepository
    extends MongoRepository<MusicRadioHistoryEvent, String> {
  List<MusicRadioHistoryEvent> findTop100ByOrderByStationSequenceDesc();
}
