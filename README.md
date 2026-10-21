# SimpleBackups fork for FTB Chunks claims

This project is a Forge 1.20.1 replacement build of
[SimpleBackups](https://github.com/MelanX/SimpleBackups). It keeps the
`simplebackups` mod ID, commands, saved backup state, configuration, messages,
and full/incremental backup modes. **Install this JAR instead of the original
SimpleBackups JAR; the two cannot be installed together.** FTB Chunks is required.

The changed file selection snapshots FTB Chunks' live server-side claims on the
Minecraft server thread before each backup. A claim selects its entire
32×32-chunk MCA region. The ZIP contains the selected `region`,
`entities`, and `poi` MCA files, any `c.x.z.mcc` sidecars in those regions,
and the FTB Chunks SNBT claim files. Other world files, including `level.dat`
and player inventories, are outside this backup's scope. Unclaimed chunks in a
selected MCA are included because the unit is the complete file.
The build targets FTB Chunks Forge 2001.3.8 for Minecraft 1.20.1.

## Commands and backup modes

- `/simplebackups backup start [quiet]` creates a manual full backup.
- `/simplebackups backup pause` and `/simplebackups backup unpause` control
  scheduled backups.
- `/simplebackups mergeBackups` merges incremental ZIPs and keeps only files
  currently selected by the claims.

`backupType` retains the original settings: `FULL_BACKUPS`,
`MODIFIED_SINCE_LAST`, and `MODIFIED_SINCE_FULL`. The two modified modes compare
whole-file modification times, not chunk contents. A fingerprint of the MCA
selection is saved after each successful backup. If that selection changes, the
next incremental backup includes all currently selected MCA files so a newly
claimed but otherwise unchanged region is not omitted. FTB Chunks SNBT files
are included in every backup, including incrementals. The original scheduling,
message, compression, error handling, and retention settings remain available
in the SimpleBackups config.

## Restore and limits

Stop the server before restoring. A backup ZIP contains paths under the world
directory name. Restoring a selected MCA replaces all 32×32 chunks in that
region, including any unclaimed chunks within it. This is a regional world-data
backup, not a standalone full-world backup. Keep a separate full-world backup
for player data, global mod state, and world metadata.

SimpleBackups writes the ZIP on a background thread after asking Minecraft to
save the world. Files that change during that copy may not form a perfectly
static snapshot. The original file-count and disk-size cleanup rules also
remain in effect; if using incremental modes, keep their full base ZIP and
all required incrementals together when restoring.

The original SimpleBackups sources are licensed under Apache 2.0; see
[LICENSE-SimpleBackups.txt](LICENSE-SimpleBackups.txt).
