# Kaizen LOG EXTRACTOR

Android archive filter with a dark liquid-glass inspired interface. It runs locally on the device, keeps the source archive untouched, and writes a fresh ZIP output into the selected `Internal storage/Kaizen_LOGS` folder.

## Supported formats

- ZIP and JAR (both read as ZIP containers)
- RAR, including common `.part01.rar` and classic `.rar` + `.r00` volume naming when all volumes are available
- 7Z, including `.7z.001`, `.7z.002`, ... multi-volume archives
- Raw numbered ZIP segments such as `.zip.001`, `.zip.002`, ...
- Classic split ZIP segments named `.z01`, `.z02`, ... plus final `.zip`

If the user selects a later volume (such as `archive.part04.rar` or `archive.7z.004`), the app orders the available pieces and can try scanning a consecutive later range without immediately rejecting it because volume 1/001 is absent. Gaps between available pieces are rejected. For 7Z, bounded end-of-archive recovery is attempted when the starting volume is missing; it may recover the file index, but it cannot reconstruct missing payload data. Other formats may still require the first volume. The folder can also be selected manually for conventional `.rar` + `.r00` sets.

## Filter modes

- **All files:** keeps regular payload files and recreates only parent directories needed by retained files; empty directory entries are omitted.
- **Card match:** same behavior, but only keeps files whose **base file name** contains `card`, case-insensitively. Directory names alone do not match.

The output container is ZIP with ZIP64 enabled as needed so individual entries or an output archive can exceed 4 GiB when the receiving app supports ZIP64.

## Safety and resource limits

- Archive file data is copied in bounded buffers; it is never read fully into RAM.
- Combined input volumes and emitted uncompressed payload are limited to 64 GiB per operation, with an additional cap of 100,000 entries and a 128 MiB 7Z decoder-memory setting. The phone's free storage, filesystem, provider, and archive codec can impose lower limits.
- ZIP/JAR/7Z/RAR signatures are checked. Loose TXT, MP3, and files that merely have a renamed extension are rejected.
- Absolute paths, drive-qualified paths, NUL bytes, and `..` traversal components are rejected. ZIP symbolic links and RAR redirection/link entries are skipped. Duplicate paths are ignored.
- The app uses Android's system document picker rather than broad all-files storage access. Create `Kaizen_LOGS` in internal storage and select that exact output folder.
- Password-protected RAR files remain unsupported by the current RAR path. Some uncommon archive codecs or damaged files may also fail with a readable error.

## Verification

GitHub Actions is configured to run unit tests and build a debug APK on pushes to `main`. The source patch itself must still pass that CI job and be tested on a real Android device before treating large and split archives as fully verified.
