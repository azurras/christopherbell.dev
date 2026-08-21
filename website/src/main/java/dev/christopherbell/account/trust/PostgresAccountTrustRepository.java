package dev.christopherbell.account.trust;

import static dev.christopherbell.persistence.jooq.identity.Tables.ACCOUNT_TRUST_RELATIONSHIP;

import dev.christopherbell.account.trust.model.AccountTrustType;
import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.persistence.jooq.identity.tables.records.AccountTrustRelationshipRecord;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;

/** PostgreSQL persistence for mute and block relationships. */
@PostgresPersistence
public class PostgresAccountTrustRepository implements AccountTrustRepository {
  private final DSLContext database;

  public PostgresAccountTrustRepository(DSLContext database) {
    this.database = database;
  }

  @Override
  public AccountTrustRelationship save(AccountTrustRelationship relationship) {
    database.insertInto(ACCOUNT_TRUST_RELATIONSHIP)
        .set(ACCOUNT_TRUST_RELATIONSHIP.RELATIONSHIP_ID, relationship.getId())
        .set(ACCOUNT_TRUST_RELATIONSHIP.OWNER_ACCOUNT_ID, relationship.getOwnerAccountId())
        .set(ACCOUNT_TRUST_RELATIONSHIP.TARGET_ACCOUNT_ID, relationship.getTargetAccountId())
        .set(ACCOUNT_TRUST_RELATIONSHIP.TRUST_TYPE, relationship.getType().name())
        .set(ACCOUNT_TRUST_RELATIONSHIP.CREATED_ON, relationship.getCreatedOn() == null
            ? null : relationship.getCreatedOn().atOffset(ZoneOffset.UTC))
        .onConflict(ACCOUNT_TRUST_RELATIONSHIP.RELATIONSHIP_ID)
        .doUpdate()
        .set(ACCOUNT_TRUST_RELATIONSHIP.OWNER_ACCOUNT_ID, relationship.getOwnerAccountId())
        .set(ACCOUNT_TRUST_RELATIONSHIP.TARGET_ACCOUNT_ID, relationship.getTargetAccountId())
        .set(ACCOUNT_TRUST_RELATIONSHIP.TRUST_TYPE, relationship.getType().name())
        .set(ACCOUNT_TRUST_RELATIONSHIP.CREATED_ON, relationship.getCreatedOn() == null
            ? null : relationship.getCreatedOn().atOffset(ZoneOffset.UTC))
        .set(ACCOUNT_TRUST_RELATIONSHIP.VERSION,
            ACCOUNT_TRUST_RELATIONSHIP.VERSION.plus(1L))
        .execute();
    return findByOwnerAccountIdAndTargetAccountIdAndType(
        relationship.getOwnerAccountId(), relationship.getTargetAccountId(), relationship.getType())
        .orElseThrow();
  }

  @Override
  public Optional<AccountTrustRelationship> findByOwnerAccountIdAndTargetAccountIdAndType(
      String ownerAccountId, String targetAccountId, AccountTrustType type) {
    return database.selectFrom(ACCOUNT_TRUST_RELATIONSHIP)
        .where(exact(ownerAccountId, targetAccountId, type))
        .fetchOptional(PostgresAccountTrustRepository::map);
  }

  @Override
  public List<AccountTrustRelationship> findByOwnerAccountIdAndTypeIn(
      String ownerAccountId, Collection<AccountTrustType> types) {
    if (types.isEmpty()) return List.of();
    return database.selectFrom(ACCOUNT_TRUST_RELATIONSHIP)
        .where(ACCOUNT_TRUST_RELATIONSHIP.OWNER_ACCOUNT_ID.eq(ownerAccountId)
            .and(ACCOUNT_TRUST_RELATIONSHIP.TRUST_TYPE.in(names(types))))
        .fetch(PostgresAccountTrustRepository::map);
  }

  @Override
  public List<AccountTrustRelationship> findByTargetAccountIdAndOwnerAccountIdInAndType(
      String targetAccountId, Collection<String> ownerAccountIds, AccountTrustType type) {
    if (ownerAccountIds.isEmpty()) return List.of();
    return database.selectFrom(ACCOUNT_TRUST_RELATIONSHIP)
        .where(ACCOUNT_TRUST_RELATIONSHIP.TARGET_ACCOUNT_ID.eq(targetAccountId)
            .and(ACCOUNT_TRUST_RELATIONSHIP.OWNER_ACCOUNT_ID.in(ownerAccountIds))
            .and(ACCOUNT_TRUST_RELATIONSHIP.TRUST_TYPE.eq(type.name())))
        .fetch(PostgresAccountTrustRepository::map);
  }

  @Override
  public List<AccountTrustRelationship> findByOwnerAccountIdAndTargetAccountIdInAndTypeIn(
      String ownerAccountId,
      Collection<String> targetAccountIds,
      Collection<AccountTrustType> types) {
    if (targetAccountIds.isEmpty() || types.isEmpty()) return List.of();
    return database.selectFrom(ACCOUNT_TRUST_RELATIONSHIP)
        .where(ACCOUNT_TRUST_RELATIONSHIP.OWNER_ACCOUNT_ID.eq(ownerAccountId)
            .and(ACCOUNT_TRUST_RELATIONSHIP.TARGET_ACCOUNT_ID.in(targetAccountIds))
            .and(ACCOUNT_TRUST_RELATIONSHIP.TRUST_TYPE.in(names(types))))
        .fetch(PostgresAccountTrustRepository::map);
  }

  @Override
  public boolean existsByOwnerAccountIdAndTargetAccountIdAndType(
      String ownerAccountId, String targetAccountId, AccountTrustType type) {
    return database.fetchExists(
        ACCOUNT_TRUST_RELATIONSHIP, exact(ownerAccountId, targetAccountId, type));
  }

  @Override
  public void deleteByOwnerAccountIdAndTargetAccountIdAndType(
      String ownerAccountId, String targetAccountId, AccountTrustType type) {
    database.deleteFrom(ACCOUNT_TRUST_RELATIONSHIP)
        .where(exact(ownerAccountId, targetAccountId, type))
        .execute();
  }

  private static org.jooq.Condition exact(
      String ownerAccountId, String targetAccountId, AccountTrustType type) {
    return ACCOUNT_TRUST_RELATIONSHIP.OWNER_ACCOUNT_ID.eq(ownerAccountId)
        .and(ACCOUNT_TRUST_RELATIONSHIP.TARGET_ACCOUNT_ID.eq(targetAccountId))
        .and(ACCOUNT_TRUST_RELATIONSHIP.TRUST_TYPE.eq(type.name()));
  }

  private static List<String> names(Collection<AccountTrustType> types) {
    return types.stream().map(Enum::name).toList();
  }

  private static AccountTrustRelationship map(AccountTrustRelationshipRecord record) {
    return AccountTrustRelationship.builder()
        .id(record.getRelationshipId())
        .ownerAccountId(record.getOwnerAccountId())
        .targetAccountId(record.getTargetAccountId())
        .type(AccountTrustType.valueOf(record.getTrustType()))
        .createdOn(instant(record.getCreatedOn()))
        .build();
  }

  private static java.time.Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
