package dev.christopherbell.account;

import static dev.christopherbell.persistence.jooq.identity.Tables.ACCOUNT;
import static dev.christopherbell.persistence.jooq.identity.Tables.ACCOUNT_FEDERATION_IDENTITY;
import static dev.christopherbell.persistence.jooq.identity.Tables.ACCOUNT_MODERATION_AUDIT;
import static dev.christopherbell.persistence.jooq.identity.Tables.ACCOUNT_MODERATION_AUDIT_VALUE;
import static dev.christopherbell.persistence.jooq.identity.Tables.ACCOUNT_PERMISSION;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountPermission;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.libs.moderation.ModerationAuditCommand;
import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.federation.api.EncryptedPrivateKey;
import dev.christopherbell.federation.api.FederationIdentity;
import dev.christopherbell.persistence.jooq.identity.tables.records.AccountRecord;
import dev.christopherbell.persistence.jooq.identity.tables.records.AccountFederationIdentityRecord;
import dev.christopherbell.persistence.jooq.identity.tables.records.AccountModerationAuditRecord;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.HashMap;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** PostgreSQL implementation of the service-facing account persistence port. */
@PostgresPersistence
public class PostgresAccountRepository implements AccountRepository {
  private final DSLContext database;

  public PostgresAccountRepository(DSLContext database) {
    this.database = database;
  }

  @Override
  public Account save(Account account) {
    try {
      var saved = database.transactionResult(
          configuration -> save(DSL.using(configuration), account));
      account.setVersion(saved.getVersion());
      return saved;
    } catch (org.jooq.exception.IntegrityConstraintViolationException failure) {
      if ("23505".equals(failure.sqlState())) {
        throw new DuplicateKeyException("PostgreSQL rejected a duplicate account identity", failure);
      }
      throw failure;
    }
  }

  private static Account save(DSLContext transaction, Account account) {
    int affected = account.getVersion() == null
        ? insert(transaction, account)
        : update(transaction, account);
    if (affected != 1) {
      throw new OptimisticLockingFailureException(
          "Account " + account.getId() + " was changed by another writer");
    }
    replacePermissions(transaction, account);
    replaceFederationIdentity(transaction, account);
    replaceModerationAudit(transaction, account);
    return findById(transaction, account.getId()).orElseThrow();
  }

  private static int insert(DSLContext transaction, Account account) {
    return transaction.insertInto(ACCOUNT)
        .set(ACCOUNT.ACCOUNT_ID, account.getId())
        .set(ACCOUNT.CREATED_BY, account.getCreatedBy())
        .set(ACCOUNT.CREATED_ON, timestamp(account.getCreatedOn()))
        .set(ACCOUNT.EMAIL, account.getEmail())
        .set(ACCOUNT.NORMALIZED_EMAIL, normalize(account.getEmail()))
        .set(ACCOUNT.FEDERATION_ENABLED, account.isFederationEnabled())
        .set(ACCOUNT.FEDERATION_ENABLED_ON, timestamp(account.getFederationEnabledOn()))
        .set(ACCOUNT.FIRST_NAME, account.getFirstName())
        .set(ACCOUNT.INVITE_CODE, account.getInviteCode())
        .set(ACCOUNT.INVITE_CODE_OWNER, account.getInviteCodeOwner())
        .set(ACCOUNT.LAST_LOGIN_ON, timestamp(account.getLastLoginOn()))
        .set(ACCOUNT.LAST_NAME, account.getLastName())
        .set(ACCOUNT.LAST_MODIFIED_BY, account.getLastModifiedBy())
        .set(ACCOUNT.LAST_UPDATED_ON, timestamp(account.getLastUpdatedOn()))
        .set(ACCOUNT.LOGIN_TOKEN, account.getLoginToken())
        .set(ACCOUNT.PASSWORD_SALT, account.getPasswordSalt())
        .set(ACCOUNT.PASSWORD_HASH, account.getPasswordHash())
        .set(ACCOUNT.PASSWORD_RESET_TOKEN_HASH, account.getPasswordResetTokenHash())
        .set(ACCOUNT.PASSWORD_RESET_TOKEN_EXPIRES_ON,
            timestamp(account.getPasswordResetTokenExpiresOn()))
        .set(ACCOUNT.ROLE, account.getRole().name())
        .set(ACCOUNT.STATUS, account.getStatus().name())
        .set(ACCOUNT.USERNAME, account.getUsername())
        .set(ACCOUNT.VERSION, 0L)
        .execute();
  }

  private static int update(DSLContext transaction, Account account) {
    return transaction.update(ACCOUNT)
        .set(ACCOUNT.CREATED_BY, account.getCreatedBy())
        .set(ACCOUNT.CREATED_ON, timestamp(account.getCreatedOn()))
        .set(ACCOUNT.EMAIL, account.getEmail())
        .set(ACCOUNT.NORMALIZED_EMAIL, normalize(account.getEmail()))
        .set(ACCOUNT.FEDERATION_ENABLED, account.isFederationEnabled())
        .set(ACCOUNT.FEDERATION_ENABLED_ON, timestamp(account.getFederationEnabledOn()))
        .set(ACCOUNT.FIRST_NAME, account.getFirstName())
        .set(ACCOUNT.INVITE_CODE, account.getInviteCode())
        .set(ACCOUNT.INVITE_CODE_OWNER, account.getInviteCodeOwner())
        .set(ACCOUNT.LAST_LOGIN_ON, timestamp(account.getLastLoginOn()))
        .set(ACCOUNT.LAST_NAME, account.getLastName())
        .set(ACCOUNT.LAST_MODIFIED_BY, account.getLastModifiedBy())
        .set(ACCOUNT.LAST_UPDATED_ON, timestamp(account.getLastUpdatedOn()))
        .set(ACCOUNT.LOGIN_TOKEN, account.getLoginToken())
        .set(ACCOUNT.PASSWORD_SALT, account.getPasswordSalt())
        .set(ACCOUNT.PASSWORD_HASH, account.getPasswordHash())
        .set(ACCOUNT.PASSWORD_RESET_TOKEN_HASH, account.getPasswordResetTokenHash())
        .set(ACCOUNT.PASSWORD_RESET_TOKEN_EXPIRES_ON,
            timestamp(account.getPasswordResetTokenExpiresOn()))
        .set(ACCOUNT.ROLE, account.getRole().name())
        .set(ACCOUNT.STATUS, account.getStatus().name())
        .set(ACCOUNT.USERNAME, account.getUsername())
        .set(ACCOUNT.VERSION, ACCOUNT.VERSION.plus(1L))
        .where(ACCOUNT.ACCOUNT_ID.eq(account.getId())
            .and(ACCOUNT.VERSION.eq(account.getVersion())))
        .execute();
  }

  private static void replacePermissions(DSLContext transaction, Account account) {
    transaction.deleteFrom(ACCOUNT_PERMISSION)
        .where(ACCOUNT_PERMISSION.ACCOUNT_ID.eq(account.getId()))
        .execute();
    var permissions = account.getPermissions();
    if (permissions == null || permissions.isEmpty()) return;
    var insert = transaction.insertInto(
        ACCOUNT_PERMISSION, ACCOUNT_PERMISSION.ACCOUNT_ID, ACCOUNT_PERMISSION.PERMISSION);
    for (var permission : permissions) {
      insert = insert.values(account.getId(), permission.name());
    }
    insert.execute();
  }

  private static void replaceFederationIdentity(DSLContext transaction, Account account) {
    transaction.deleteFrom(ACCOUNT_FEDERATION_IDENTITY)
        .where(ACCOUNT_FEDERATION_IDENTITY.ACCOUNT_ID.eq(account.getId()))
        .execute();
    var identity = account.getFederationIdentity();
    if (identity == null) return;
    transaction.insertInto(ACCOUNT_FEDERATION_IDENTITY)
        .set(ACCOUNT_FEDERATION_IDENTITY.ACCOUNT_ID, account.getId())
        .set(ACCOUNT_FEDERATION_IDENTITY.ACTOR_ID, identity.actorId())
        .set(ACCOUNT_FEDERATION_IDENTITY.KEY_ID, identity.keyId())
        .set(ACCOUNT_FEDERATION_IDENTITY.PUBLIC_KEY_PEM, identity.publicKeyPem())
        .set(ACCOUNT_FEDERATION_IDENTITY.PRIVATE_KEY_NONCE, identity.encryptedPrivateKey().nonce())
        .set(ACCOUNT_FEDERATION_IDENTITY.PRIVATE_KEY_CIPHERTEXT,
            identity.encryptedPrivateKey().ciphertext())
        .set(ACCOUNT_FEDERATION_IDENTITY.KEY_VERSION, identity.keyVersion())
        .set(ACCOUNT_FEDERATION_IDENTITY.CREATED_ON, timestamp(identity.createdOn()))
        .execute();
  }

  private static void replaceModerationAudit(DSLContext transaction, Account account) {
    transaction.deleteFrom(ACCOUNT_MODERATION_AUDIT)
        .where(ACCOUNT_MODERATION_AUDIT.ACCOUNT_ID.eq(account.getId()))
        .execute();
    var audit = account.getPendingModerationAudit();
    if (audit == null) return;
    transaction.insertInto(ACCOUNT_MODERATION_AUDIT)
        .set(ACCOUNT_MODERATION_AUDIT.ACCOUNT_ID, account.getId())
        .set(ACCOUNT_MODERATION_AUDIT.EVENT_ID, audit.eventId())
        .set(ACCOUNT_MODERATION_AUDIT.ACTOR_ACCOUNT_ID, audit.actorAccountId())
        .set(ACCOUNT_MODERATION_AUDIT.ACTOR_USERNAME, audit.actorUsername())
        .set(ACCOUNT_MODERATION_AUDIT.ACTION, audit.action())
        .set(ACCOUNT_MODERATION_AUDIT.TARGET_TYPE, audit.targetType())
        .set(ACCOUNT_MODERATION_AUDIT.TARGET_ID, audit.targetId())
        .set(ACCOUNT_MODERATION_AUDIT.TARGET_LABEL, audit.targetLabel())
        .set(ACCOUNT_MODERATION_AUDIT.REASON, audit.reason())
        .set(ACCOUNT_MODERATION_AUDIT.MESSAGE, audit.message())
        .execute();
    insertAuditValues(transaction, account.getId(), "before", audit.beforeValues());
    insertAuditValues(transaction, account.getId(), "after", audit.afterValues());
    insertAuditValues(transaction, account.getId(), "metadata", audit.metadata());
  }

  private static void insertAuditValues(
      DSLContext transaction, String accountId, String partition, Map<String, String> values) {
    if (values.isEmpty()) return;
    var insert = transaction.insertInto(
        ACCOUNT_MODERATION_AUDIT_VALUE,
        ACCOUNT_MODERATION_AUDIT_VALUE.ACCOUNT_ID,
        ACCOUNT_MODERATION_AUDIT_VALUE.PARTITION_NAME,
        ACCOUNT_MODERATION_AUDIT_VALUE.VALUE_KEY,
        ACCOUNT_MODERATION_AUDIT_VALUE.VALUE);
    for (var entry : values.entrySet()) {
      insert = insert.values(accountId, partition, entry.getKey(), entry.getValue());
    }
    insert.execute();
  }

  @Override
  public Optional<Account> findById(String id) {
    return findById(database, id);
  }

  private static Optional<Account> findById(DSLContext context, String id) {
    return context.selectFrom(ACCOUNT)
        .where(ACCOUNT.ACCOUNT_ID.eq(id))
        .fetchOptional(record -> map(context, record));
  }

  @Override
  public boolean existsById(String id) {
    return database.fetchExists(ACCOUNT, ACCOUNT.ACCOUNT_ID.eq(id));
  }

  @Override
  public void deleteById(String id) {
    database.deleteFrom(ACCOUNT).where(ACCOUNT.ACCOUNT_ID.eq(id)).execute();
  }

  @Override
  public Page<Account> findAll(Pageable pageable) {
    return page(ACCOUNT.ACCOUNT_ID.isNotNull(), pageable);
  }

  @Override
  public List<Account> findAllById(Iterable<String> ids) {
    var requested = new ArrayList<String>();
    ids.forEach(requested::add);
    if (requested.isEmpty()) return List.of();
    var records = database.selectFrom(ACCOUNT)
        .where(ACCOUNT.ACCOUNT_ID.in(requested))
        .fetch();
    return mapAll(database, records);
  }

  @Override
  public Optional<Account> findByEmail(String email) {
    return findOne(ACCOUNT.EMAIL.eq(email), false);
  }

  @Override
  public Optional<Account> findByEmailIgnoreCase(String email) {
    return findOne(ACCOUNT.NORMALIZED_EMAIL.eq(normalize(email)), false);
  }

  @Override
  public Optional<Account> findByPasswordResetTokenHash(String passwordResetTokenHash) {
    return findOne(ACCOUNT.PASSWORD_RESET_TOKEN_HASH.eq(passwordResetTokenHash), false);
  }

  @Override
  public Optional<Account> findByUsername(String username) {
    return findOne(ACCOUNT.USERNAME.eq(username), false);
  }

  @Override
  public Optional<Account> findByUsernameAndStatus(String username, AccountStatus status) {
    return findOne(ACCOUNT.USERNAME.eq(username).and(ACCOUNT.STATUS.eq(status.name())), false);
  }

  @Override
  public Optional<Account> findByUsernameIgnoreCase(String username) {
    return findOne(DSL.lower(ACCOUNT.USERNAME).eq(normalize(username)), true);
  }

  @Override
  public Optional<Account> findByUsernameIgnoreCaseAndStatusAndFederationEnabledTrue(
      String username, AccountStatus status) {
    return findOne(DSL.lower(ACCOUNT.USERNAME).eq(normalize(username))
        .and(ACCOUNT.STATUS.eq(status.name()))
        .and(ACCOUNT.FEDERATION_ENABLED.isTrue()), true);
  }

  @Override
  public long countByStatus(AccountStatus status) {
    return database.fetchCount(ACCOUNT, ACCOUNT.STATUS.eq(status.name()));
  }

  @Override
  public Page<Account> findByStatus(AccountStatus status, Pageable pageable) {
    return page(ACCOUNT.STATUS.eq(status.name()), pageable);
  }

  @Override
  public List<Account> findByUsernameStartingWithIgnoreCaseAndStatusOrderByUsernameAsc(
      String usernamePrefix, AccountStatus status, Pageable pageable) {
    var records = database.selectFrom(ACCOUNT)
        .where(DSL.lower(ACCOUNT.USERNAME).like(escapeLike(normalize(usernamePrefix)) + '%', '\\')
            .and(ACCOUNT.STATUS.eq(status.name())))
        .orderBy(ACCOUNT.USERNAME.asc(), ACCOUNT.ACCOUNT_ID.asc())
        .limit(pageable.isPaged() ? pageable.getPageSize() : Integer.MAX_VALUE)
        .offset(pageable.isPaged() ? Math.toIntExact(pageable.getOffset()) : 0)
        .fetch();
    return mapAll(database, records);
  }

  @Override
  public List<Account> findByIdInAndStatusAndFederationEnabledTrueOrderByUsernameAsc(
      Collection<String> accountIds, AccountStatus status, Pageable pageable) {
    if (accountIds.isEmpty()) return List.of();
    var records = database.selectFrom(ACCOUNT)
        .where(ACCOUNT.ACCOUNT_ID.in(accountIds)
            .and(ACCOUNT.STATUS.eq(status.name()))
            .and(ACCOUNT.FEDERATION_ENABLED.isTrue()))
        .orderBy(ACCOUNT.USERNAME.asc(), ACCOUNT.ACCOUNT_ID.asc())
        .limit(pageable.isPaged() ? pageable.getPageSize() : Integer.MAX_VALUE)
        .offset(pageable.isPaged() ? Math.toIntExact(pageable.getOffset()) : 0)
        .fetch();
    return mapAll(database, records);
  }

  private Optional<Account> findOne(org.jooq.Condition condition, boolean requireUnique) {
    var records = database.selectFrom(ACCOUNT)
        .where(condition)
        .limit(requireUnique ? 2 : 1)
        .fetch();
    var matches = mapAll(database, records);
    if (requireUnique && matches.size() > 1) {
      throw new IncorrectResultSizeDataAccessException(1);
    }
    return matches.stream().findFirst();
  }

  private Page<Account> page(org.jooq.Condition condition, Pageable pageable) {
    var total = database.fetchCount(ACCOUNT, condition);
    var query = database.selectFrom(ACCOUNT)
        .where(condition)
        .orderBy(sortFields(pageable));
    var records = pageable.isPaged()
        ? query.limit(pageable.getPageSize()).offset(Math.toIntExact(pageable.getOffset()))
            .fetch()
        : query.fetch();
    var values = mapAll(database, records);
    return new PageImpl<>(values, pageable, total);
  }

  private static List<SortField<?>> sortFields(Pageable pageable) {
    var fields = new ArrayList<SortField<?>>();
    for (var order : pageable.getSort()) {
      var field = sortableField(order.getProperty());
      fields.add(order.getDirection() == Sort.Direction.ASC ? field.asc() : field.desc());
    }
    if (fields.isEmpty()) fields.add(ACCOUNT.ACCOUNT_ID.asc());
    if (fields.stream().noneMatch(field -> field.getName().equals(ACCOUNT.ACCOUNT_ID.getName()))) {
      fields.add(ACCOUNT.ACCOUNT_ID.asc());
    }
    return List.copyOf(fields);
  }

  private static Field<?> sortableField(String property) {
    return switch (property) {
      case "id" -> ACCOUNT.ACCOUNT_ID;
      case "createdOn" -> ACCOUNT.CREATED_ON;
      case "lastUpdatedOn" -> ACCOUNT.LAST_UPDATED_ON;
      case "username" -> ACCOUNT.USERNAME;
      default -> throw new IllegalArgumentException("Unsupported account sort property: " + property);
    };
  }

  public static Account map(DSLContext context, AccountRecord record) {
    return mapAll(context, List.of(record)).getFirst();
  }

  public static List<Account> mapAll(DSLContext context, List<AccountRecord> records) {
    if (records.isEmpty()) return List.of();
    var accountIds = records.stream().map(AccountRecord::getAccountId).toList();
    var permissions = new HashMap<String, HashSet<AccountPermission>>();
    accountIds.forEach(id -> permissions.put(id, new HashSet<>()));
    context.select(ACCOUNT_PERMISSION.ACCOUNT_ID, ACCOUNT_PERMISSION.PERMISSION)
        .from(ACCOUNT_PERMISSION)
        .where(ACCOUNT_PERMISSION.ACCOUNT_ID.in(accountIds))
        .forEach(row -> permissions.get(row.value1()).add(AccountPermission.valueOf(row.value2())));
    Map<String, AccountFederationIdentityRecord> identities = context
        .selectFrom(ACCOUNT_FEDERATION_IDENTITY)
        .where(ACCOUNT_FEDERATION_IDENTITY.ACCOUNT_ID.in(accountIds))
        .fetchMap(ACCOUNT_FEDERATION_IDENTITY.ACCOUNT_ID);
    Map<String, AccountModerationAuditRecord> audits = context
        .selectFrom(ACCOUNT_MODERATION_AUDIT)
        .where(ACCOUNT_MODERATION_AUDIT.ACCOUNT_ID.in(accountIds))
        .fetchMap(ACCOUNT_MODERATION_AUDIT.ACCOUNT_ID);
    var auditValues = new HashMap<AuditPartition, LinkedHashMap<String, String>>();
    context.selectFrom(ACCOUNT_MODERATION_AUDIT_VALUE)
        .where(ACCOUNT_MODERATION_AUDIT_VALUE.ACCOUNT_ID.in(accountIds))
        .orderBy(ACCOUNT_MODERATION_AUDIT_VALUE.ACCOUNT_ID.asc(),
            ACCOUNT_MODERATION_AUDIT_VALUE.PARTITION_NAME.asc(),
            ACCOUNT_MODERATION_AUDIT_VALUE.VALUE_KEY.asc())
        .forEach(row -> auditValues.computeIfAbsent(
            new AuditPartition(row.getAccountId(), row.getPartitionName()),
            ignored -> new LinkedHashMap<>()).put(row.getValueKey(), row.getValue()));
    return records.stream().map(record -> map(
        record,
        permissions.get(record.getAccountId()),
        federationIdentity(identities.get(record.getAccountId())),
        moderationAudit(audits.get(record.getAccountId()), auditValues))).toList();
  }

  private static Account map(
      AccountRecord record,
      HashSet<AccountPermission> permissions,
      FederationIdentity federationIdentity,
      ModerationAuditCommand moderationAudit) {
    return Account.builder()
        .id(record.getAccountId())
        .version(record.getVersion())
        .createdBy(record.getCreatedBy())
        .createdOn(instant(record.getCreatedOn()))
        .email(record.getEmail())
        .federationEnabled(record.getFederationEnabled())
        .federationEnabledOn(instant(record.getFederationEnabledOn()))
        .federationIdentity(federationIdentity)
        .firstName(record.getFirstName())
        .inviteCode(record.getInviteCode())
        .inviteCodeOwner(record.getInviteCodeOwner())
        .lastLoginOn(instant(record.getLastLoginOn()))
        .lastName(record.getLastName())
        .lastModifiedBy(record.getLastModifiedBy())
        .lastUpdatedOn(instant(record.getLastUpdatedOn()))
        .loginToken(record.getLoginToken())
        .passwordSalt(record.getPasswordSalt())
        .passwordHash(record.getPasswordHash())
        .passwordResetTokenHash(record.getPasswordResetTokenHash())
        .passwordResetTokenExpiresOn(instant(record.getPasswordResetTokenExpiresOn()))
        .pendingModerationAudit(moderationAudit)
        .role(Role.valueOf(record.getRole()))
        .permissions(permissions)
        .status(AccountStatus.valueOf(record.getStatus()))
        .username(record.getUsername())
        .build();
  }

  private static FederationIdentity federationIdentity(AccountFederationIdentityRecord identity) {
    if (identity == null) return null;
    return new FederationIdentity(
        identity.getActorId(), identity.getKeyId(), identity.getPublicKeyPem(),
        new EncryptedPrivateKey(identity.getPrivateKeyNonce(), identity.getPrivateKeyCiphertext()),
        identity.getKeyVersion(), instant(identity.getCreatedOn()));
  }

  private static ModerationAuditCommand moderationAudit(
      AccountModerationAuditRecord audit,
      Map<AuditPartition, LinkedHashMap<String, String>> values) {
    if (audit == null) return null;
    return new ModerationAuditCommand(
        audit.getEventId(), audit.getActorAccountId(), audit.getActorUsername(),
        audit.getAction(), audit.getTargetType(), audit.getTargetId(), audit.getTargetLabel(),
        audit.getReason(), audit.getMessage(),
        auditValues(values, audit.getAccountId(), "before"),
        auditValues(values, audit.getAccountId(), "after"),
        auditValues(values, audit.getAccountId(), "metadata"));
  }

  private static Map<String, String> auditValues(
      Map<AuditPartition, LinkedHashMap<String, String>> values,
      String accountId,
      String partition) {
    return Map.copyOf(values.getOrDefault(
        new AuditPartition(accountId, partition), new LinkedHashMap<>()));
  }

  private static String normalize(String value) {
    return value.toLowerCase(Locale.ROOT);
  }

  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static OffsetDateTime timestamp(Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  private record AuditPartition(String accountId, String partition) {}
}
