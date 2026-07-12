package dev.christopherbell.admin.commandcenter.action;

import lombok.extern.slf4j.Slf4j;

/** Safe default executor that records intent without touching the host. */
@Slf4j
public class SimulatedCommandExecutor implements CommandExecutor {
  @Override
  public void execute(CommandCenterActionType action) {
    log.info("Simulated command-center action: {}", action);
  }
}
