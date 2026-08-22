package dev.christopherbell.account.trust;

import dev.christopherbell.account.trust.model.AccountTrustType;
import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL persistence for mute and block relationships. */
@PostgresPersistence
public class PostgresAccountTrustRepository implements AccountTrustRepository {
  private final JdbcClient database;
  private final String table;

  public PostgresAccountTrustRepository(JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("identity", "account_trust_relationship");
  }

  @Override
  public AccountTrustRelationship save(AccountTrustRelationship relationship) {
    var statement = database.sql("""
            insert into %s
              (relationship_id, owner_account_id, target_account_id, trust_type, created_on)
            values (:id, :ownerId, :targetId, :type, :createdOn)
            on conflict (relationship_id) do update set
              owner_account_id = excluded.owner_account_id,
              target_account_id = excluded.target_account_id,
              trust_type = excluded.trust_type,
              created_on = excluded.created_on,
              version = %s.version + 1
            """.formatted(table, table))
        .param("id", relationship.getId())
        .param("ownerId", relationship.getOwnerAccountId())
        .param("targetId", relationship.getTargetAccountId())
        .param("type", relationship.getType().name());
    if (relationship.getCreatedOn() == null) {
      statement.param("createdOn", null, Types.TIMESTAMP_WITH_TIMEZONE).update();
    } else {
      statement.param("createdOn", relationship.getCreatedOn().atOffset(ZoneOffset.UTC)).update();
    }
    return findByOwnerAccountIdAndTargetAccountIdAndType(
        relationship.getOwnerAccountId(), relationship.getTargetAccountId(), relationship.getType())
        .orElseThrow();
  }

  @Override
  public Optional<AccountTrustRelationship> findByOwnerAccountIdAndTargetAccountIdAndType(
      String ownerAccountId, String targetAccountId, AccountTrustType type) {
    return database.sql("select * from %s where %s".formatted(table, exact()))
        .param("ownerId", ownerAccountId)
        .param("targetId", targetAccountId)
        .param("type", type.name())
        .query(PostgresAccountTrustRepository::map)
        .optional();
  }

  @Override
  public List<AccountTrustRelationship> findByOwnerAccountIdAndTypeIn(
      String ownerAccountId, Collection<AccountTrustType> types) {
    if (types.isEmpty()) return List.of();
    return database.sql("""
            select * from %s where owner_account_id = :ownerId and trust_type in (:types)
            """.formatted(table))
        .param("ownerId", ownerAccountId)
        .param("types", names(types))
        .query(PostgresAccountTrustRepository::map)
        .list();
  }

  @Override
  public List<AccountTrustRelationship> findByTargetAccountIdAndOwnerAccountIdInAndType(
      String targetAccountId, Collection<String> ownerAccountIds, AccountTrustType type) {
    if (ownerAccountIds.isEmpty()) return List.of();
    return database.sql("""
            select * from %s
            where target_account_id = :targetId
              and owner_account_id in (:ownerIds)
              and trust_type = :type
            """.formatted(table))
        .param("targetId", targetAccountId)
        .param("ownerIds", ownerAccountIds)
        .param("type", type.name())
        .query(PostgresAccountTrustRepository::map)
        .list();
  }

  @Override
  public List<AccountTrustRelationship> findByOwnerAccountIdAndTargetAccountIdInAndTypeIn(
      String ownerAccountId,
      Collection<String> targetAccountIds,
      Collection<AccountTrustType> types) {
    if (targetAccountIds.isEmpty() || types.isEmpty()) return List.of();
    return database.sql("""
            select * from %s
            where owner_account_id = :ownerId
              and target_account_id in (:targetIds)
              and trust_type in (:types)
            """.formatted(table))
        .param("ownerId", ownerAccountId)
        .param("targetIds", targetAccountIds)
        .param("types", names(types))
        .query(PostgresAccountTrustRepository::map)
        .list();
  }

  @Override
  public boolean existsByOwnerAccountIdAndTargetAccountIdAndType(
      String ownerAccountId, String targetAccountId, AccountTrustType type) {
    return database.sql("select exists (select 1 from %s where %s)".formatted(table, exact()))
        .param("ownerId", ownerAccountId)
        .param("targetId", targetAccountId)
        .param("type", type.name())
        .query(Boolean.class)
        .single();
  }

  @Override
  public void deleteByOwnerAccountIdAndTargetAccountIdAndType(
      String ownerAccountId, String targetAccountId, AccountTrustType type) {
    database.sql("delete from %s where %s".formatted(table, exact()))
        .param("ownerId", ownerAccountId)
        .param("targetId", targetAccountId)
        .param("type", type.name())
        .update();
  }

  private static String exact() {
    return "owner_account_id = :ownerId and target_account_id = :targetId and trust_type = :type";
  }

  private static List<String> names(Collection<AccountTrustType> types) {
    return types.stream().map(Enum::name).toList();
  }

  private static AccountTrustRelationship map(java.sql.ResultSet row, int rowNumber)
      throws SQLException {
    return AccountTrustRelationship.builder()
        .id(row.getString("relationship_id"))
        .ownerAccountId(row.getString("owner_account_id"))
        .targetAccountId(row.getString("target_account_id"))
        .type(AccountTrustType.valueOf(row.getString("trust_type")))
        .createdOn(instant(row.getObject("created_on", OffsetDateTime.class)))
        .build();
  }

  private static java.time.Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
