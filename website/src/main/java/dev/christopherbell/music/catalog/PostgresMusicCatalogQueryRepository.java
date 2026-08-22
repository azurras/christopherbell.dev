package dev.christopherbell.music.catalog;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL query implementation for the bounded Music catalog. */
@PostgresPersistence
public class PostgresMusicCatalogQueryRepository implements MusicCatalogQueryRepository {
  private final JdbcClient database;
  private final String table;

  public PostgresMusicCatalogQueryRepository(JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("music", "track");
  }

  @Override
  public MusicCatalogResult search(MusicQuery request) {
    MusicQuery query = request == null
        ? new MusicQuery(null, null, null, null, null, null, 0, 50) : request;
    var filter = filter(query);
    long total = count(filter);
    int totalPages = MusicCatalogResultSupport.totalPages(total, query.size());
    int page = totalPages == 0 ? 0 : Math.min(query.page(), totalPages - 1);
    var statement = statement("""
        select * from %s where %s
        order by album_artist asc nulls last, album asc nulls last,
          disc_number asc nulls last, track_number asc nulls last, title asc, track_id asc
        limit :limit offset :offset
        """.formatted(table, filter.where()), filter.parameters())
        .param("limit", query.size()).param("offset", page * query.size());
    List<MusicTrack> matches = statement.query(PostgresMusicTrackRepository::map).list();
    return new MusicCatalogResult(
        matches, MusicCatalogResultSupport.albums(matches), facets(filter),
        page, query.size(), total, totalPages);
  }

  @Override
  public List<MusicTrack> radioCandidates(int requestedLimit) {
    int limit = Math.max(1, Math.min(10_000, requestedLimit));
    return database.sql("""
            select * from %s
            where missing_since is null and index_status = 'READY' and not excluded_from_radio
            order by track_id asc limit :limit
            """.formatted(table))
        .param("limit", limit).query(PostgresMusicTrackRepository::map).list();
  }

  private Filter filter(MusicQuery query) {
    var clauses = new ArrayList<String>();
    var parameters = new HashMap<String, Object>();
    clauses.add("missing_since is null");
    clauses.add("index_status = 'READY'");
    if (query.text() != null) {
      clauses.add("""
          (lower(title) like :text escape '!'
            or lower(artist) like :text escape '!'
            or lower(album_artist) like :text escape '!'
            or lower(album) like :text escape '!'
            or lower(genre) like :text escape '!'
            or lower(relative_path) like :text escape '!')
          """);
      parameters.put("text", "%" + escapeLike(query.text().toLowerCase(java.util.Locale.ROOT)) + "%");
    }
    if (query.artist() != null) { clauses.add("artist = :artist"); parameters.put("artist", query.artist()); }
    if (query.album() != null) { clauses.add("album = :album"); parameters.put("album", query.album()); }
    if (query.genre() != null) { clauses.add("genre = :genre"); parameters.put("genre", query.genre()); }
    if (query.favorite() != null) { clauses.add("favorite = :favorite"); parameters.put("favorite", query.favorite()); }
    if (query.trackIds() != null) { clauses.add("track_id in (:trackIds)"); parameters.put("trackIds", query.trackIds()); }
    return new Filter(String.join(" and ", clauses), Map.copyOf(parameters));
  }

  private long count(Filter filter) {
    return statement("select count(*) from %s where %s".formatted(table, filter.where()),
        filter.parameters()).query(Long.class).single();
  }

  private MusicFacets facets(Filter filter) {
    return new MusicFacets(
        strings("artist", filter), strings("album", filter), strings("genre", filter),
        statement("""
            select distinct release_year from %s where %s and release_year is not null
            order by release_year asc
            """.formatted(table, filter.where()), filter.parameters())
            .query(Integer.class).list());
  }

  private List<String> strings(String column, Filter filter) {
    return MusicCatalogResultSupport.strings(statement("""
            select distinct %s from %s where %s and %s is not null
            """.formatted(column, table, filter.where(), column), filter.parameters())
        .query(String.class).list());
  }

  private JdbcClient.StatementSpec statement(String sql, Map<String, ?> parameters) {
    var result = database.sql(sql);
    for (var entry : parameters.entrySet()) result.param(entry.getKey(), entry.getValue());
    return result;
  }

  private static String escapeLike(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  private record Filter(String where, Map<String, Object> parameters) {}
}
