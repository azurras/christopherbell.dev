package dev.christopherbell.admin.activity;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.admin.model.AdminActivity;
import dev.christopherbell.permission.PermissionService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import dev.christopherbell.libs.api.exception.ServiceUnavailableException;

@RequiredArgsConstructor
@Service
public class AdminActivityService {
  private final AccountRepository accountRepository;
  private final AdminActivityRepository adminActivityRepository;
  private final Clock clock;
  private final PermissionService permissionService;

  public List<AdminActivity> getRecentActivity() {
    return adminActivityRepository.findTop25ByOrderByCreatedOnDesc();
  }

  public AdminActivity record(
      String action,
      String targetType,
      String targetId,
      String targetLabel,
      String message,
      Map<String, String> metadata
  ) {
    var actorId = permissionService.getSelfId();
    var actorUsername = accountRepository.findById(actorId)
        .map(account -> account.getUsername() == null ? actorId : account.getUsername())
        .orElse(actorId);

    return recordForActor(
        actorId, actorUsername, action, targetType, targetId, targetLabel, message, metadata);
  }

  /** Records one validated primary moderation event and surfaces persistence failure safely. */
  public AdminActivity recordModeration(ModerationAuditCommand command) {
    var actorId = permissionService.getSelfId();
    var actorUsername = accountRepository.findById(actorId)
        .map(account -> account.getUsername() == null ? actorId : account.getUsername())
        .orElse(actorId);
    var activity = AdminActivity.builder()
        .actorAccountId(actorId)
        .actorUsername(actorUsername)
        .action(command.action())
        .targetType(command.targetType())
        .targetId(command.targetId())
        .targetLabel(command.targetLabel())
        .reason(command.reason())
        .message(command.message().formatted(actorUsername))
        .beforeValues(command.beforeValues())
        .afterValues(command.afterValues())
        .metadata(command.metadata())
        .createdOn(Instant.now(clock))
        .build();
    try {
      return adminActivityRepository.save(activity);
    } catch (RuntimeException failure) {
      throw new ServiceUnavailableException(
          "Moderation audit is temporarily unavailable.", failure);
    }
  }

  /** Records an outcome for an explicitly captured actor outside request security context. */
  public AdminActivity recordForActor(
      String actorId,
      String actorUsername,
      String action,
      String targetType,
      String targetId,
      String targetLabel,
      String message,
      Map<String, String> metadata
  ) {
    return adminActivityRepository.save(AdminActivity.builder()
        .actorAccountId(actorId)
        .actorUsername(actorUsername)
        .action(action)
        .targetType(targetType)
        .targetId(targetId)
        .targetLabel(targetLabel)
        .message(message.formatted(actorUsername))
        .metadata(Map.copyOf(metadata))
        .createdOn(Instant.now(clock))
        .build());
  }
}
