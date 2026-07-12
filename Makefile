.PHONY: prod-install prod-deploy prod-status prod-logs prod-restart prod-releases prod-rollback prod-backup prod-verify-startup prod-uninstall prod-auto-install prod-auto-deploy prod-auto-status prod-auto-remove prod-cloudflare-upgrade

prod-install prod-deploy prod-status prod-logs prod-restart prod-releases prod-rollback prod-backup prod-verify-startup prod-uninstall prod-auto-install prod-auto-deploy prod-auto-status prod-auto-remove:
	@cmd.exe /d /c prod.cmd $(@:prod-%=%)

prod-cloudflare-upgrade:
	@winget upgrade --id Cloudflare.cloudflared --exact --source winget --scope machine --accept-package-agreements --accept-source-agreements
	@powershell.exe -NoLogo -NoProfile -Command "Restart-Service cloudflared; if ((Get-Service cloudflared).Status -ne 'Running') { exit 1 }"
