package dev.christopherbell.music.radio;

import java.util.List;

/** Persistence port for bounded global radio history. */
public interface MusicRadioHistoryRepository {
  MusicRadioHistoryEvent save(MusicRadioHistoryEvent event);
  boolean existsById(String id);
  List<MusicRadioHistoryEvent> findTop100ByOrderByStationSequenceDesc();
}
