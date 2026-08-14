package dev.christopherbell.music.catalog;

import static dev.christopherbell.persistence.jooq.music.Tables.TRACK;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import java.util.ArrayList;
import java.util.List;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;

/** PostgreSQL query implementation for the bounded Music catalog. */
@PostgresPersistence
public class PostgresMusicCatalogQueryRepository implements MusicCatalogQueryRepository {
  private final DSLContext database;

  public PostgresMusicCatalogQueryRepository(DSLContext database) {
    this.database = database;
  }

  @Override
  public MusicCatalogResult search(MusicQuery request) {
    MusicQuery query = request == null
        ? new MusicQuery(null, null, null, null, null, null, 0, 50) : request;
    Condition condition = condition(query);
    long total = database.fetchCount(TRACK, condition);
    int totalPages = MusicCatalogResultSupport.totalPages(total, query.size());
    int page = totalPages == 0 ? 0 : Math.min(query.page(), totalPages - 1);
    List<MusicTrack> matches = database.selectFrom(TRACK).where(condition)
        .orderBy(TRACK.ALBUM_ARTIST.asc().nullsLast(), TRACK.ALBUM.asc().nullsLast(),
            TRACK.DISC_NUMBER.asc().nullsLast(), TRACK.TRACK_NUMBER.asc().nullsLast(),
            TRACK.TITLE.asc(), TRACK.TRACK_ID.asc())
        .limit(query.size()).offset(page * query.size()).fetch(PostgresMusicTrackRepository::map);
    return new MusicCatalogResult(matches, MusicCatalogResultSupport.albums(matches),
        facets(condition), page, query.size(), total, totalPages);
  }

  @Override
  public List<MusicTrack> radioCandidates(int requestedLimit) {
    int limit = Math.max(1, Math.min(10_000, requestedLimit));
    return database.selectFrom(TRACK)
        .where(TRACK.MISSING_SINCE.isNull().and(TRACK.INDEX_STATUS.eq(MusicIndexStatus.READY.name()))
            .and(TRACK.EXCLUDED_FROM_RADIO.isFalse()))
        .orderBy(TRACK.TRACK_ID.asc()).limit(limit).fetch(PostgresMusicTrackRepository::map);
  }

  private Condition condition(MusicQuery query) {
    Condition result = TRACK.MISSING_SINCE.isNull()
        .and(TRACK.INDEX_STATUS.eq(MusicIndexStatus.READY.name()));
    if (query.text() != null) {
      String literal = "%" + escapeLike(query.text().toLowerCase(java.util.Locale.ROOT)) + "%";
      result = result.and(DSL.lower(TRACK.TITLE).like(literal, '!')
          .or(DSL.lower(TRACK.ARTIST).like(literal, '!'))
          .or(DSL.lower(TRACK.ALBUM_ARTIST).like(literal, '!'))
          .or(DSL.lower(TRACK.ALBUM).like(literal, '!'))
          .or(DSL.lower(TRACK.GENRE).like(literal, '!'))
          .or(DSL.lower(TRACK.RELATIVE_PATH).like(literal, '!')));
    }
    if (query.artist() != null) result = result.and(TRACK.ARTIST.eq(query.artist()));
    if (query.album() != null) result = result.and(TRACK.ALBUM.eq(query.album()));
    if (query.genre() != null) result = result.and(TRACK.GENRE.eq(query.genre()));
    if (query.favorite() != null) result = result.and(TRACK.FAVORITE.eq(query.favorite()));
    if (query.trackIds() != null) result = result.and(TRACK.TRACK_ID.in(query.trackIds()));
    return result;
  }

  private MusicFacets facets(Condition condition) {
    return new MusicFacets(strings(TRACK.ARTIST, condition), strings(TRACK.ALBUM, condition),
        strings(TRACK.GENRE, condition), database.selectDistinct(TRACK.RELEASE_YEAR).from(TRACK)
            .where(condition.and(TRACK.RELEASE_YEAR.isNotNull())).orderBy(TRACK.RELEASE_YEAR.asc())
            .fetch(TRACK.RELEASE_YEAR));
  }

  private List<String> strings(Field<String> field, Condition condition) {
    return MusicCatalogResultSupport.strings(database.selectDistinct(field).from(TRACK)
        .where(condition.and(field.isNotNull())).fetch(field));
  }

  private static String escapeLike(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }
}
