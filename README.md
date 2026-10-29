# Kaizen LOG EXTRACTOR

Android app with a soft purple, interactive UI. The app filters a ZIP or RAR archive and writes a **new ZIP** into the user-selected `Kaizen_LOGS` folder. The input archive is never modified.

## Modes

- **Default:** keeps all regular files, omitting explicit empty-directory entries. Parent directories are recreated only when a kept file needs them.
- **Cards:** same behavior, but keeps only files whose **base file name** contains `card`, case-insensitively. A directory name containing `card` does not make a file match.

## Safety / limitations

- Only `.zip` and `.rar` files with matching archive signatures are accepted; TXT/MP3 and renamed non-archives are rejected.
- ZIP is read by Java's `ZipFile`; RAR reading uses Junrar 8.1.2. Output is always ZIP.
- Password-protected RAR archives are not supported in this version.
- To limit archive-bomb/resource risks, input files are capped at 2 GiB, archives at 100,000 entries, and total emitted uncompressed payload at 1 GiB.
- Absolute paths and `..` path components are rejected. RAR link entries are not copied. Duplicate file names are skipped.
- Storage access is granted with Android's system document picker; the app does not request broad all-files access. Create `/storage/emulated/0/Kaizen_LOGS/` in the Android file manager first, then select that exact folder in the app.
- The source has not been built or device-tested in this bundle. GitHub Actions builds a debug APK and stores it as an artifact on each push to `main`.

## Manual behavior checks

1. `kaizen/lmao/hahaha/xd/` only: no output archive is retained because there are no regular files.
2. `kaizen/hahah/xdxd/xd.txt` in Default: file and its parent directories remain.
3. `kaizen/hahah/xdxd/xd.txt` in Cards: file is skipped.
4. `kaizen/hahah/xdxd/cardxdxd.txt` in Cards: file and parents remain.
5. `kaizen/hahah/xfjsifh/cards.txt` in Cards: file and parents remain.
6. `kaizen/hahah/xdxd/lmfao/` with no file beneath: omitted.
7. `CARD.txt` in Cards: kept because the match is case-insensitive.
8. A `.txt` file with no ZIP/RAR signature: rejected before output creation.
