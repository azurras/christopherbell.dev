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
import dev.christopherbell.admin.activity.ModerationAuditCommand;
import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.federation.identity.EncryptedPrivateKey;
import dev.christopherbell.federation.identity.FederationIdentity;
import dev.christopherbell.persistence.jooq.identity.tables.records.AccountRecord;
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
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** PostgreSQL implementation of the service-facing account persistence port. */
@PostgresPersistence
public final class PostgresAccountRepository implements AccountRepository {
  private final DSLContext database;

  public PostgresAccountRepository(DSLContext database) {
    this.database = database;
  }

  @Override
  public Account save(Account account) {
    return database.transactionResult(configuration -> save(DSL.using(configuration), account));
  }

  private static Account save(DSLContext transaction, Account account) {
    transaction.insertInto(ACCOUNT)
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
        .onConflict(ACCOUNT.ACCOUNT_ID)
        .doUpdate()
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
        .execute();
    replacePermissions(transaction, account);
    replaceFederationIdentity(transaction, account);
    replaceModerationAudit(transaction, account);
    return findById(transaction, account.getId()).orElseThrow();
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
    return database.selectFrom(ACCOUNT)
        .where(ACCOUNT.ACCOUNT_ID.in(requested))
        .fetch(record -> map(database, record));
  }

  @Override
  public Optional<Account> findByEmail(String email) {
    return findOne(ACCOUNT.EMAIL.eq(email), false);
  }

  @Override
  public Optional<Account> findByEmailIgnoreCase(String email) {
    return findOne(DSL.lower(ACCOUNT.EMAIL).eq(normalize(email)), false);
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
    return database.selectFrom(ACCOUNT)
        .where(DSL.lower(ACCOUNT.USERNAME).like(escapeLike(normalize(usernamePrefix)) + '%', '\\')
            .and(ACCOUNT.STATUS.eq(status.name())))
        .orderBy(ACCOUNT.USERNAME.asc(), ACCOUNT.ACCOUNT_ID.asc())
        .limit(pageable.isPaged() ? pageable.getPageSize() : Integer.MAX_VALUE)
        .offset(pageable.isPaged() ? Math.toIntExact(pageable.getOffset()) : 0)
        .fetch(record -> map(database, record));
  }

  @Override
  public List<Account> findByIdInAndStatusAndFederationEnabledTrueOrderByUsernameAsc(
      Collection<String> accountIds, AccountStatus status, Pageable pageable) {
    if (accountIds.isEmpty()) return List.of();
    return database.selectFrom(ACCOUNT)
        .where(ACCOUNT.ACCOUNT_ID.in(accountIds)
            .and(ACCOUNT.STATUS.eq(status.name()))
            .and(ACCOUNT.FEDERATION_ENABLED.isTrue()))
        .orderBy(ACCOUNT.USERNAME.asc(), ACCOUNT.ACCOUNT_ID.asc())
        .limit(pageable.isPaged() ? pageable.getPageSize() : Integer.MAX_VALUE)
        .offset(pageable.isPaged() ? Math.toIntExact(pageable.getOffset()) : 0)
        .fetch(record -> map(database, record));
  }

  private Optional<Account> findOne(org.jooq.Condition condition, boolean requireUnique) {
    var matches = database.selectFrom(ACCOUNT)
        .where(condition)
        .limit(requireUnique ? 2 : 1)
        .fetch(record -> map(database, record));
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
    var values = pageable.isPaged()
        ? query.limit(pageable.getPageSize()).offset(Math.toIntExact(pageable.getOffset()))
            .fetch(record -> map(database, record))
        : query.fetch(record -> map(database, record));
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
    var permissions = context.select(ACCOUNT_PERMISSION.PERMISSION)
        .from(ACCOUNT_PERMISSION)
        .where(ACCOUNT_PERMISSION.ACCOUNT_ID.eq(record.getAccountId()))
        .fetchSet(ACCOUNT_PERMISSION.PERMISSION).stream()
        .map(AccountPermission::valueOf)
        .collect(java.util.stream.Collectors.toCollection(HashSet::new));
    var federationIdentity = context.selectFrom(ACCOUNT_FEDERATION_IDENTITY)
        .where(ACCOUNT_FEDERATION_IDENTITY.ACCOUNT_ID.eq(record.getAccountId()))
        .fetchOptional(identity -> new FederationIdentity(
            identity.getActorId(),
            identity.getKeyId(),
            identity.getPublicKeyPem(),
            new EncryptedPrivateKey(
                identity.getPrivateKeyNonce(), identity.getPrivateKeyCiphertext()),
            identity.getKeyVersion(),
            instant(identity.getCreatedOn())))
        .orElse(null);
    var moderationAudit = context.selectFrom(ACCOUNT_MODERATION_AUDIT)
        .where(ACCOUNT_MODERATION_AUDIT.ACCOUNT_ID.eq(record.getAccountId()))
        .fetchOptional(audit -> new ModerationAuditCommand(
            audit.getEventId(), audit.getActorAccountId(), audit.getActorUsername(),
            audit.getAction(), audit.getTargetType(), audit.getTargetId(), audit.getTargetLabel(),
            audit.getReason(), audit.getMessage(),
            auditValues(context, record.getAccountId(), "before"),
            auditValues(context, record.getAccountId(), "after"),
            auditValues(context, record.getAccountId(), "metadata")))
        .orElse(null);
    return Account.builder()
        .id(record.getAccountId())
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

  private static Map<String, String> auditValues(
      DSLContext context, String accountId, String partition) {
    var values = new LinkedHashMap<String, String>();
    context.select(
            ACCOUNT_MODERATION_AUDIT_VALUE.VALUE_KEY,
            ACCOUNT_MODERATION_AUDIT_VALUE.VALUE)
        .from(ACCOUNT_MODERATION_AUDIT_VALUE)
        .where(ACCOUNT_MODERATION_AUDIT_VALUE.ACCOUNT_ID.eq(accountId)
            .and(ACCOUNT_MODERATION_AUDIT_VALUE.PARTITION_NAME.eq(partition)))
        .orderBy(ACCOUNT_MODERATION_AUDIT_VALUE.VALUE_KEY.asc())
        .forEach(row -> values.put(row.value1(), row.value2()));
    return Map.copyOf(values);
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
}
