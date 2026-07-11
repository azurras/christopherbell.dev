.PHONY: prod-install prod-migrate prod-deploy prod-status prod-logs prod-restart prod-releases prod-rollback prod-backup prod-uninstall prod-auto-install prod-auto-deploy prod-auto-status prod-auto-remove

prod-install prod-migrate prod-deploy prod-status prod-logs prod-restart prod-releases prod-rollback prod-backup prod-uninstall prod-auto-install prod-auto-deploy prod-auto-status prod-auto-remove:
	@cmd.exe /d /c prod.cmd $(@:prod-%=%)
