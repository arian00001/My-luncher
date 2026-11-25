# Backup — original launcher

The launcher exactly as it was before the marketplace work. Nothing was deleted
or rewritten to make this backup.

| | |
|---|---|
| Snapshot commit | `da0bb1e` (full: `da0bb1e9848a56365a39468702bc8f671a675a63`) |
| Branch | `backup/original-launcher` |
| Tag | `original-launcher-v1` |
| Archive | `backups/original-launcher-backup.zip` (65 KB, 25 entries) |

## Download links

- ZIP download: https://github.com/arian00001/My-luncher/raw/main/backups/original-launcher-backup.zip
- RAW download: https://raw.githubusercontent.com/arian00001/My-luncher/main/backups/original-launcher-backup.zip
- Whole branch as ZIP (GitHub): https://github.com/arian00001/My-luncher/archive/refs/heads/backup/original-launcher.zip
- Tag as ZIP (GitHub): https://github.com/arian00001/My-luncher/archive/refs/tags/original-launcher-v1.zip

## Restore the original launcher from the backup

Say:

> RESTORE MY ORIGINAL LAUNCHER FROM THIS BACKUP LINK: https://raw.githubusercontent.com/arian00001/My-luncher/main/backups/original-launcher-backup.zip

or, without the file, use the branch/tag:

```bash
git fetch origin backup/original-launcher
git checkout -B restore/original-launcher origin/backup/original-launcher
```

Or from the tag:

```bash
git checkout -B restore/original-launcher original-launcher-v1
```

The archive restores every file of the project: `app/` (the Android launcher,
Kotlin + Gradle), `src/` (the web frontend the WebView loads), `base44/`,
`package.json`, `vite.config.js`, `wrangler.jsonc` and the Gradle wrapper.

## Notes

- The backup is a snapshot of commit `da0bb1e`; later commits (the
  marketplace) never touch it.
- Build the restored launcher with `./gradlew assembleDebug` inside `app/`.
