# Telegram / TDLib integration

## Behavior

- The Telegram screen connects to the user's own Telegram account through TDLib (phone number, login code, and optional two-step password).
- The chat picker combines TDLib's Main and Archive lists and up to 10 custom folder lists. Chat IDs are de-duplicated; the screen loads at most 500 chats and requests chat details in batches of 20.
- For a selected chat, the app scans up to 20,000 unique recent history messages, in pages of at most 100. A short TDLib response is not treated as end-of-history when its message cursor still advances.
- The app displays up to the newest five supported, single-attachment ZIP, JAR, RAR, and 7Z archives. Numbered multi-volume archives are not supported in this Telegram path. Files whose exact TDLib size is unknown are skipped rather than using the approximate expected size as a seekable length.
- The selection screen transfers only TDLib file IDs, exact sizes, and sanitized names. It does not download the selected archives.
- During processing, `TelegramSeekableByteChannel` requests only the current 1 MiB range through `DownloadFile(offset, limit, synchronous=true)`, then reads those bytes using `ReadFilePart` into `TdApi.Data.data`. ZIP/JAR, 7Z, and RAR readers seek through the channel as needed.
- TDLib stores downloaded ranges in its app-private file cache. The app does not wait for a complete archive download and does not create a second complete archive copy. The app-side range cache is bounded to one 1 MiB chunk, in addition to compression-library buffers.
- Range requests have a 180-second timeout, honor user cancellation, validate exact byte counts, and surface TDLib errors. An incomplete output is deleted. If a multi-archive batch fails, earlier outputs created by that same batch are also removed so retrying does not create duplicates.
- The existing local SAF archive picker and output-folder selection remain available and use the existing local-file flow.

## Setup

1. Open Telegram's official developer portal: https://my.telegram.org/apps.
2. Create or select your own application and copy its API ID and API Hash.
3. In Kaizen LOG EXTRACTOR, open **Telegram'dan arşiv seç** and enter those values. Do not publish or send your API Hash to anyone.
4. Complete the phone-number and login-code prompts. Enter the two-step password only when Telegram requests it.
5. Select a chat and choose one or more of the newest listed archive attachments.
6. Return to the main screen, select the output folder, and start processing. The application requests archive ranges as its parser needs them.

The app does not embed API credentials. API Hash and the TDLib database key are encrypted with Android Keystore AES-GCM and stored in private app preferences. Login phone numbers, codes, and two-step passwords are not written to app logs. Android backup is disabled for the application.

## Current limitations

- Email-based login, new-account registration, and QR/other-device confirmation are not implemented; the app displays an explicit unsupported-state message.
- At most 500 chats are loaded for the picker. Only the Main, Archive, and up to 10 custom folder lists provided by TDLib updates are included.
- At most 20,000 recent unique messages are scanned per selected chat; the UI returns the latest five matching archives.
- Exact file sizes are required for safe random access. A document with unknown `file.size` is omitted; its approximate `expectedSize` is not treated as exact.
- The Telegram import path supports a single archive attachment at a time per archive. Numbered/multi-volume archives must be handled through the existing local multi-volume picker.
- The complete Android build, real Telegram account flow, cancellation during live network traffic, and real archive fixtures must still be verified in a configured Android SDK environment and on a device.

## Native dependency and licenses

The Android AAR is pinned to an immutable JitPack commit of `capullo-tech/lib-tdlib-android`, a third-party mirror that redistributes prebuilt TDLib Android binaries and the generated Java API. The mirror's NOTICE identifies the upstream TGX-Android/tdlib commit and the included ABI set. TDLib is under the Boost Software License 1.0; OpenSSL is under Apache License 2.0. See `app/src/main/assets/third_party_licenses/`.
