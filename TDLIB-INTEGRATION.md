# Telegram / TDLib integration

## Behavior

- The Telegram screen connects to the user's own Telegram account through TDLib (phone number, login code, and optional two-step password).
- The app loads up to 50 chats from the **main chat list**, then inspects up to the latest 50 messages in the chat selected by the user.
- Only single-file `.zip`, `.jar`, `.rar`, and `.7z` document attachments are listed. Numbered/split volume files are omitted because this screen imports one attachment per selection.
- The user chooses one attachment. TDLib downloads it into its app-private files directory; the app verifies the path, copies it with a bounded buffer into a private import directory, and passes it to the existing local archive processor.
- The existing local archive picker, output-folder selection, archive modes, and Format Cards behavior are kept. The Telegram temporary copy is deleted after extraction, failure, or cancellation.
- Leaving the Telegram import screen during a pending download requests cancellation. A download timeout is also enforced.

## Setup

1. Open Telegram's official developer portal: https://my.telegram.org/apps.
2. Create or select your own application and copy its API ID and API Hash.
3. In Kaizen LOG EXTRACTOR, open **Telegram'dan arşiv seç** and enter those values. Do not publish or send your API Hash to anyone.
4. Complete the phone-number and login-code prompts. Enter the two-step password only when Telegram requests it.
5. Select a chat, then select one listed archive attachment.
6. Return to the main screen, select the existing local filter mode and output folder, and start processing.

The app does not embed API credentials. API Hash and the TDLib database key are encrypted with Android Keystore AES-GCM and stored in private app preferences. Login phone numbers, codes, and two-step passwords are not written to app logs. Android backup is disabled for the application.

## Current limitations

- Email-based login, new-account registration, and QR/other-device confirmation are not implemented; the app displays an explicit unsupported-state message.
- The chat list is the main chat list, not archived/folder chat lists.
- Only one archive attachment is downloaded per selection. Split archives must be handled through the existing local multi-volume picker.
- The full Android build and live account/download flow must still be verified by GitHub Actions and on a real Android device. This patch authoring environment does not contain Gradle or the Android SDK.

## Native dependency and licenses

The Android AAR is pinned to an immutable JitPack commit of `capullo-tech/lib-tdlib-android`, a third-party mirror that redistributes prebuilt TDLib Android binaries and the generated Java API. The mirror's NOTICE identifies the upstream TGX-Android/tdlib commit and the included ABI set. TDLib is under the Boost Software License 1.0; OpenSSL is under Apache License 2.0. See `app/src/main/assets/third_party_licenses/`.
