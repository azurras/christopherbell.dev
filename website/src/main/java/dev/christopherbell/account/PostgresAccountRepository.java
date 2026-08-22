package dev.christopherbell.account;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountPermission;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.federation.api.EncryptedPrivateKey;
import dev.christopherbell.federation.api.FederationIdentity;
import dev.christopherbell.libs.moderation.ModerationAuditCommand;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** PostgreSQL implementation of the service-facing account persistence port. */
@PostgresPersistence
public class PostgresAccountRepository implements AccountRepository {
  private final JdbcClient database;
  private final TransactionOperations transactions;
  private final String accountTable;
  private final String permissionTable;
  private final String federationIdentityTable;
  private final String moderationAuditTable;
  private final String moderationAuditValueTable;

  public PostgresAccountRepository(
      JdbcClient database, PostgresqlSchemaNames schemas, TransactionOperations transactions) {
    this.database = database;
    this.transactions = transactions;
    accountTable = schemas.qualifiedTable("identity", "account");
    permissionTable = schemas.qualifiedTable("identity", "account_permission");
    federationIdentityTable = schemas.qualifiedTable("identity", "account_federation_identity");
    moderationAuditTable = schemas.qualifiedTable("identity", "account_moderation_audit");
    moderationAuditValueTable = schemas.qualifiedTable("identity", "account_moderation_audit_value");
  }

  @Override
  public Account save(Account account) {
    try {
      var saved = transactions.execute(ignored -> saveInTransaction(account));
      if (saved == null) {
        throw new IllegalStateException("Account transaction returned no value");
      }
      account.setVersion(saved.getVersion());
      return saved;
    } catch (DataIntegrityViolationException failure) {
      if ("23505".equals(sqlState(failure))) {
        throw new DuplicateKeyException("PostgreSQL rejected a duplicate account identity", failure);
      }
      throw failure;
    }
  }

  private Account saveInTransaction(Account account) {
    int affected = account.getVersion() == null ? insert(account) : update(account);
    if (affected != 1) {
      throw new OptimisticLockingFailureException(
          "Account " + account.getId() + " was changed by another writer");
    }
    replacePermissions(account);
    replaceFederationIdentity(account);
    replaceModerationAudit(account);
    return findById(account.getId()).orElseThrow();
  }

  private int insert(Account account) {
    return database.sql("""
            insert into %s (
              account_id, created_by, created_on, email, normalized_email,
              federation_enabled, federation_enabled_on, first_name, invite_code,
              invite_code_owner, last_login_on, last_name, last_modified_by,
              last_updated_on, login_token, password_salt, password_hash,
              password_reset_token_hash, password_reset_token_expires_on, role,
              status, username, version)
            values (
              :id, :createdBy, :createdOn, :email, :normalizedEmail,
              :federationEnabled, :federationEnabledOn, :firstName, :inviteCode,
              :inviteCodeOwner, :lastLoginOn, :lastName, :lastModifiedBy,
              :lastUpdatedOn, :loginToken, :passwordSalt, :passwordHash,
              :passwordResetTokenHash, :passwordResetTokenExpiresOn, :role,
              :status, :username, 0)
            """.formatted(accountTable))
        .paramSource(parameters(account))
        .update();
  }

  private int update(Account account) {
    return database.sql("""
            update %s set
              created_by = :createdBy,
              created_on = :createdOn,
              email = :email,
              normalized_email = :normalizedEmail,
              federation_enabled = :federationEnabled,
              federation_enabled_on = :federationEnabledOn,
              first_name = :firstName,
              invite_code = :inviteCode,
              invite_code_owner = :inviteCodeOwner,
              last_login_on = :lastLoginOn,
              last_name = :lastName,
              last_modified_by = :lastModifiedBy,
              last_updated_on = :lastUpdatedOn,
              login_token = :loginToken,
              password_salt = :passwordSalt,
              password_hash = :passwordHash,
              password_reset_token_hash = :passwordResetTokenHash,
              password_reset_token_expires_on = :passwordResetTokenExpiresOn,
              role = :role,
              status = :status,
              username = :username,
              version = version + 1
            where account_id = :id and version = :version
            """.formatted(accountTable))
        .paramSource(parameters(account).addValue("version", account.getVersion()))
        .update();
  }

  private static MapSqlParameterSource parameters(Account account) {
    return new MapSqlParameterSource()
        .addValue("id", account.getId())
        .addValue("createdBy", account.getCreatedBy(), Types.VARCHAR)
        .addValue("createdOn", timestamp(account.getCreatedOn()), Types.TIMESTAMP_WITH_TIMEZONE)
        .addValue("email", account.getEmail())
        .addValue("normalizedEmail", normalize(account.getEmail()))
        .addValue("federationEnabled", account.isFederationEnabled())
        .addValue("federationEnabledOn", timestamp(account.getFederationEnabledOn()),
            Types.TIMESTAMP_WITH_TIMEZONE)
        .addValue("firstName", account.getFirstName(), Types.VARCHAR)
        .addValue("inviteCode", account.getInviteCode(), Types.OTHER)
        .addValue("inviteCodeOwner", account.getInviteCodeOwner(), Types.OTHER)
        .addValue("lastLoginOn", timestamp(account.getLastLoginOn()), Types.TIMESTAMP_WITH_TIMEZONE)
        .addValue("lastName", account.getLastName(), Types.VARCHAR)
        .addValue("lastModifiedBy", account.getLastModifiedBy(), Types.VARCHAR)
        .addValue("lastUpdatedOn", timestamp(account.getLastUpdatedOn()),
            Types.TIMESTAMP_WITH_TIMEZONE)
        .addValue("loginToken", account.getLoginToken(), Types.VARCHAR)
        .addValue("passwordSalt", account.getPasswordSalt(), Types.VARCHAR)
        .addValue("passwordHash", account.getPasswordHash())
        .addValue("passwordResetTokenHash", account.getPasswordResetTokenHash(), Types.VARCHAR)
        .addValue("passwordResetTokenExpiresOn", timestamp(account.getPasswordResetTokenExpiresOn()),
            Types.TIMESTAMP_WITH_TIMEZONE)
        .addValue("role", account.getRole().name())
        .addValue("status", account.getStatus().name())
        .addValue("username", account.getUsername());
  }

  private void replacePermissions(Account account) {
    database.sql("delete from %s where account_id = :id".formatted(permissionTable))
        .param("id", account.getId()).update();
    if (account.getPermissions() == null) return;
    for (var permission : account.getPermissions()) {
      database.sql("insert into %s (account_id, permission) values (:id, :permission)"
              .formatted(permissionTable))
          .param("id", account.getId()).param("permission", permission.name()).update();
    }
  }

  private void replaceFederationIdentity(Account account) {
    database.sql("delete from %s where account_id = :id".formatted(federationIdentityTable))
        .param("id", account.getId()).update();
    var identity = account.getFederationIdentity();
    if (identity == null) return;
    database.sql("""
            insert into %s (
              account_id, actor_id, key_id, public_key_pem, private_key_nonce,
              private_key_ciphertext, key_version, created_on)
            values (
              :accountId, :actorId, :keyId, :publicKeyPem, :nonce,
              :ciphertext, :keyVersion, :createdOn)
            """.formatted(federationIdentityTable))
        .param("accountId", account.getId())
        .param("actorId", identity.actorId())
        .param("keyId", identity.keyId())
        .param("publicKeyPem", identity.publicKeyPem())
        .param("nonce", identity.encryptedPrivateKey().nonce())
        .param("ciphertext", identity.encryptedPrivateKey().ciphertext())
        .param("keyVersion", identity.keyVersion())
        .param("createdOn", timestamp(identity.createdOn()))
        .update();
  }

  private void replaceModerationAudit(Account account) {
    database.sql("delete from %s where account_id = :id".formatted(moderationAuditTable))
        .param("id", account.getId()).update();
    var audit = account.getPendingModerationAudit();
    if (audit == null) return;
    database.sql("""
            insert into %s (
              account_id, event_id, actor_account_id, actor_username, action,
              target_type, target_id, target_label, reason, message)
            values (
              :accountId, :eventId, :actorAccountId, :actorUsername, :action,
              :targetType, :targetId, :targetLabel, :reason, :message)
            """.formatted(moderationAuditTable))
        .paramSource(new MapSqlParameterSource()
            .addValue("accountId", account.getId())
            .addValue("eventId", audit.eventId())
            .addValue("actorAccountId", audit.actorAccountId(), Types.VARCHAR)
            .addValue("actorUsername", audit.actorUsername(), Types.VARCHAR)
            .addValue("action", audit.action())
            .addValue("targetType", audit.targetType())
            .addValue("targetId", audit.targetId())
            .addValue("targetLabel", audit.targetLabel(), Types.VARCHAR)
            .addValue("reason", audit.reason(), Types.VARCHAR)
            .addValue("message", audit.message(), Types.VARCHAR))
        .update();
    insertAuditValues(account.getId(), "before", audit.beforeValues());
    insertAuditValues(account.getId(), "after", audit.afterValues());
    insertAuditValues(account.getId(), "metadata", audit.metadata());
  }

  private void insertAuditValues(String accountId, String partition, Map<String, String> values) {
    for (var entry : values.entrySet()) {
      database.sql("""
              insert into %s (account_id, partition_name, value_key, value)
              values (:accountId, :partition, :key, :value)
              """.formatted(moderationAuditValueTable))
          .param("accountId", accountId).param("partition", partition)
          .param("key", entry.getKey()).param("value", entry.getValue()).update();
    }
  }

  @Override
  public Optional<Account> findById(String id) {
    return findOne("account_id = :value", Map.of("value", id), false);
  }

  @Override
  public boolean existsById(String id) {
    return database.sql("select exists (select 1 from %s where account_id = :id)"
            .formatted(accountTable))
        .param("id", id).query(Boolean.class).single();
  }

  @Override
  public void deleteById(String id) {
    database.sql("delete from %s where account_id = :id".formatted(accountTable))
        .param("id", id).update();
  }

  @Override
  public Page<Account> findAll(Pageable pageable) {
    return page("true", Map.of(), pageable);
  }

  @Override
  public List<Account> findAllById(Iterable<String> ids) {
    var requested = new ArrayList<String>();
    ids.forEach(requested::add);
    if (requested.isEmpty()) return List.of();
    return queryAccounts("account_id in (:values)", Map.of("values", requested), "");
  }

  @Override
  public Optional<Account> findByEmail(String email) {
    return findOne("email = :value", Map.of("value", email), false);
  }

  @Override
  public Optional<Account> findByEmailIgnoreCase(String email) {
    return findOne("normalized_email = :value", Map.of("value", normalize(email)), true);
  }

  @Override
  public Optional<Account> findByPasswordResetTokenHash(String hash) {
    return findOne("password_reset_token_hash = :value", Map.of("value", hash), false);
  }

  @Override
  public Optional<Account> findByUsername(String username) {
    return findOne("username = :value", Map.of("value", username), false);
  }

  @Override
  public Optional<Account> findByUsernameAndStatus(String username, AccountStatus status) {
    return findOne("username = :username and status = :status",
        Map.of("username", username, "status", status.name()), false);
  }

  @Override
  public Optional<Account> findByUsernameIgnoreCase(String username) {
    return findOne("lower(username) = :value", Map.of("value", normalize(username)), true);
  }

  @Override
  public Optional<Account> findByUsernameIgnoreCaseAndStatusAndFederationEnabledTrue(
      String username, AccountStatus status) {
    return findOne("lower(username) = :username and status = :status and federation_enabled",
        Map.of("username", normalize(username), "status", status.name()), true);
  }

  @Override
  public long countByStatus(AccountStatus status) {
    return count("status = :status", Map.of("status", status.name()));
  }

  @Override
  public Page<Account> findByStatus(AccountStatus status, Pageable pageable) {
    return page("status = :status", Map.of("status", status.name()), pageable);
  }

  @Override
  public List<Account> findByUsernameStartingWithIgnoreCaseAndStatusOrderByUsernameAsc(
      String prefix, AccountStatus status, Pageable pageable) {
    return queryAccounts(
        "lower(username) like :prefix escape '\\\\' and status = :status",
        Map.of("prefix", escapeLike(normalize(prefix)) + '%', "status", status.name()),
        "order by username asc, account_id asc " + pageClause(pageable), pageParameters(pageable));
  }

  @Override
  public List<Account> findByIdInAndStatusAndFederationEnabledTrueOrderByUsernameAsc(
      Collection<String> accountIds, AccountStatus status, Pageable pageable) {
    if (accountIds.isEmpty()) return List.of();
    return queryAccounts(
        "account_id in (:ids) and status = :status and federation_enabled",
        Map.of("ids", accountIds, "status", status.name()),
        "order by username asc, account_id asc " + pageClause(pageable), pageParameters(pageable));
  }

  Page<Account> findAdminPage(AdminAccountQuery request) {
    var clauses = new ArrayList<String>();
    var parameters = new HashMap<String, Object>();
    if (request.status() != null) {
      clauses.add("status = :status");
      parameters.put("status", request.status().name());
    }
    if (request.role() != null) {
      clauses.add("role = :role");
      parameters.put("role", request.role().name());
    }
    if (request.text() != null) {
      clauses.add("""
          (lower(username) like :text escape '\\'
            or lower(email) like :text escape '\\'
            or lower(first_name) like :text escape '\\'
            or lower(last_name) like :text escape '\\')
          """);
      parameters.put("text", "%" + escapeLike(normalize(request.text())) + "%");
    }
    var where = clauses.isEmpty() ? "true" : String.join(" and ", clauses);
    var direction = request.direction() == Sort.Direction.ASC ? "asc" : "desc";
    var column = switch (request.sort()) {
      case "createdOn" -> "created_on";
      case "lastUpdatedOn" -> "last_updated_on";
      case "lastLoginOn" -> "last_login_on";
      case "username" -> "username";
      case "email" -> "email";
      case "status" -> "status";
      case "role" -> "role";
      default -> throw new IllegalArgumentException("Unsupported account sort field.");
    };
    var total = count(where, parameters);
    var values = queryAccounts(where, parameters,
        "order by %s %s, account_id %s limit :limit offset :offset"
            .formatted(column, direction, direction),
        Map.of("limit", request.size(), "offset", Math.multiplyExact(request.page(), request.size())));
    return new PageImpl<>(values, org.springframework.data.domain.PageRequest.of(
        request.page(), request.size()), total);
  }

  private Optional<Account> findOne(String where, Map<String, ?> parameters, boolean unique) {
    var matches = queryAccounts(where, parameters, "limit " + (unique ? 2 : 1));
    if (unique && matches.size() > 1) {
      throw new IncorrectResultSizeDataAccessException(1);
    }
    return matches.stream().findFirst();
  }

  private Page<Account> page(String where, Map<String, ?> parameters, Pageable pageable) {
    var total = count(where, parameters);
    var suffix = "order by " + sortClause(pageable) + " " + pageClause(pageable);
    var values = queryAccounts(where, parameters, suffix, pageParameters(pageable));
    return new PageImpl<>(values, pageable, total);
  }

  private long count(String where, Map<String, ?> parameters) {
    var query = database.sql("select count(*) from %s where %s".formatted(accountTable, where));
    for (var entry : parameters.entrySet()) query.param(entry.getKey(), entry.getValue());
    return query.query(Long.class).single();
  }

  private List<Account> queryAccounts(String where, Map<String, ?> parameters, String suffix) {
    return queryAccounts(where, parameters, suffix, Map.of());
  }

  private List<Account> queryAccounts(
      String where, Map<String, ?> parameters, String suffix, Map<String, ?> suffixParameters) {
    var query = database.sql("select * from %s where %s %s".formatted(accountTable, where, suffix));
    for (var entry : parameters.entrySet()) query.param(entry.getKey(), entry.getValue());
    for (var entry : suffixParameters.entrySet()) query.param(entry.getKey(), entry.getValue());
    return enrich(query.query(PostgresAccountRepository::mapBase).list());
  }

  private List<Account> enrich(List<Account> accounts) {
    if (accounts.isEmpty()) return List.of();
    var ids = accounts.stream().map(Account::getId).toList();
    var permissions = new HashMap<String, HashSet<AccountPermission>>();
    ids.forEach(id -> permissions.put(id, new HashSet<>()));
    database.sql("select account_id, permission from %s where account_id in (:ids)"
            .formatted(permissionTable))
        .param("ids", ids)
        .query((row, ignored) -> new String[] {row.getString(1), row.getString(2)})
        .list()
        .forEach(value -> permissions.get(value[0]).add(AccountPermission.valueOf(value[1])));

    var identities = new HashMap<String, FederationIdentity>();
    database.sql("select * from %s where account_id in (:ids)".formatted(federationIdentityTable))
        .param("ids", ids)
        .query((row, ignored) -> Map.entry(row.getString("account_id"), federationIdentity(row)))
        .list().forEach(value -> identities.put(value.getKey(), value.getValue()));

    var audits = new HashMap<String, AuditRow>();
    database.sql("select * from %s where account_id in (:ids)".formatted(moderationAuditTable))
        .param("ids", ids).query(PostgresAccountRepository::auditRow).list()
        .forEach(value -> audits.put(value.accountId(), value));

    var auditValues = new HashMap<AuditPartition, LinkedHashMap<String, String>>();
    database.sql("""
            select * from %s where account_id in (:ids)
            order by account_id, partition_name, value_key
            """.formatted(moderationAuditValueTable))
        .param("ids", ids)
        .query((row, ignored) -> new AuditValue(
            row.getString("account_id"), row.getString("partition_name"),
            row.getString("value_key"), row.getString("value")))
        .list()
        .forEach(value -> auditValues.computeIfAbsent(
            new AuditPartition(value.accountId(), value.partition()),
            ignored -> new LinkedHashMap<>()).put(value.key(), value.value()));

    for (var account : accounts) {
      account.setPermissions(permissions.get(account.getId()));
      account.setFederationIdentity(identities.get(account.getId()));
      account.setPendingModerationAudit(moderationAudit(audits.get(account.getId()), auditValues));
    }
    return List.copyOf(accounts);
  }

  private static Account mapBase(ResultSet row, int rowNumber) throws SQLException {
    return Account.builder()
        .id(row.getString("account_id"))
        .version(row.getLong("version"))
        .createdBy(row.getString("created_by"))
        .createdOn(instant(row.getObject("created_on", OffsetDateTime.class)))
        .email(row.getString("email"))
        .federationEnabled(row.getObject("federation_enabled", Boolean.class))
        .federationEnabledOn(instant(row.getObject("federation_enabled_on", OffsetDateTime.class)))
        .firstName(row.getString("first_name"))
        .inviteCode(row.getObject("invite_code", UUID.class))
        .inviteCodeOwner(row.getObject("invite_code_owner", UUID.class))
        .lastLoginOn(instant(row.getObject("last_login_on", OffsetDateTime.class)))
        .lastName(row.getString("last_name"))
        .lastModifiedBy(row.getString("last_modified_by"))
        .lastUpdatedOn(instant(row.getObject("last_updated_on", OffsetDateTime.class)))
        .loginToken(row.getString("login_token"))
        .passwordSalt(row.getString("password_salt"))
        .passwordHash(row.getString("password_hash"))
        .passwordResetTokenHash(row.getString("password_reset_token_hash"))
        .passwordResetTokenExpiresOn(
            instant(row.getObject("password_reset_token_expires_on", OffsetDateTime.class)))
        .role(Role.valueOf(row.getString("role")))
        .status(AccountStatus.valueOf(row.getString("status")))
        .username(row.getString("username"))
        .build();
  }

  private static FederationIdentity federationIdentity(ResultSet row) throws SQLException {
    return new FederationIdentity(
        row.getString("actor_id"), row.getString("key_id"), row.getString("public_key_pem"),
        new EncryptedPrivateKey(row.getBytes("private_key_nonce"),
            row.getBytes("private_key_ciphertext")),
        row.getInt("key_version"), instant(row.getObject("created_on", OffsetDateTime.class)));
  }

  private static AuditRow auditRow(ResultSet row, int rowNumber) throws SQLException {
    return new AuditRow(
        row.getString("account_id"), row.getString("event_id"),
        row.getString("actor_account_id"), row.getString("actor_username"),
        row.getString("action"), row.getString("target_type"), row.getString("target_id"),
        row.getString("target_label"), row.getString("reason"), row.getString("message"));
  }

  private static ModerationAuditCommand moderationAudit(
      AuditRow audit, Map<AuditPartition, LinkedHashMap<String, String>> values) {
    if (audit == null) return null;
    return new ModerationAuditCommand(
        audit.eventId(), audit.actorAccountId(), audit.actorUsername(), audit.action(),
        audit.targetType(), audit.targetId(), audit.targetLabel(), audit.reason(), audit.message(),
        auditValues(values, audit.accountId(), "before"),
        auditValues(values, audit.accountId(), "after"),
        auditValues(values, audit.accountId(), "metadata"));
  }

  private static Map<String, String> auditValues(
      Map<AuditPartition, LinkedHashMap<String, String>> values,
      String accountId, String partition) {
    return Map.copyOf(values.getOrDefault(
        new AuditPartition(accountId, partition), new LinkedHashMap<>()));
  }

  private static String sortClause(Pageable pageable) {
    var fields = new ArrayList<String>();
    for (var order : pageable.getSort()) {
      var column = switch (order.getProperty()) {
        case "id" -> "account_id";
        case "createdOn" -> "created_on";
        case "lastUpdatedOn" -> "last_updated_on";
        case "username" -> "username";
        default -> throw new IllegalArgumentException(
            "Unsupported account sort property: " + order.getProperty());
      };
      fields.add(column + (order.getDirection() == Sort.Direction.ASC ? " asc" : " desc"));
    }
    if (fields.isEmpty()) fields.add("account_id asc");
    if (fields.stream().noneMatch(value -> value.startsWith("account_id "))) {
      fields.add("account_id asc");
    }
    return String.join(", ", fields);
  }

  private static String pageClause(Pageable pageable) {
    return pageable.isPaged() ? "limit :limit offset :offset" : "";
  }

  private static Map<String, ?> pageParameters(Pageable pageable) {
    return pageable.isPaged()
        ? Map.of("limit", pageable.getPageSize(), "offset", Math.toIntExact(pageable.getOffset()))
        : Map.of();
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

  private static String sqlState(Throwable failure) {
    for (var cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlFailure) return sqlFailure.getSQLState();
    }
    return null;
  }

  private record AuditPartition(String accountId, String partition) {}
  private record AuditValue(String accountId, String partition, String key, String value) {}
  private record AuditRow(
      String accountId, String eventId, String actorAccountId, String actorUsername,
      String action, String targetType, String targetId, String targetLabel,
      String reason, String message) {}
}
