# TeleCloud

**Android-first, Telegram-assisted multi-project control and worker prototype.**

TeleCloud is an open-source experiment for managing multiple project workspaces from one Android app and using Telegram as a remote command channel. It is designed to avoid a paid VPS for the device-worker prototype.

## What is included in this first APK milestone

- Local project registry: create and manage separate project records.
- Per-project API credential generator: creates random `tc_live_` secrets, displays each secret once, and stores only its SHA-256 hash and prefix locally.
- Credential revocation: remove a locally stored key record.
- Telegram Bot API connection test using `getMe`.
- Encrypted bot-token and allow-listed chat ID storage using Android Keystore (AES-GCM).
- Optional Android foreground worker that polls Telegram and accepts commands only from the configured chat ID.
- Telegram commands:
  - `/tc help`
  - `/tc status`
  - `/tc projects`
  - `/tc ping`
- GitHub Actions workflow that builds a debug APK and uploads it as a workflow artifact. No app deployment is configured.
- GitHub Actions control screen: connect a repository with a fine-grained token, list workflows, inspect recent runs, and trigger workflows on a selected branch.

## Important boundaries

This is an early device-worker prototype, **not a drop-in Oracle Cloud replacement yet**.

- Telegram is used for remote commands and coordination. Telegram does not execute arbitrary Node.js/Python backend projects.
- The current API keys are local credentials stored in the app. They are not yet validated by a remote API gateway or attached to a hosted endpoint.
- GitHub integration currently uses a user-supplied fine-grained personal access token stored encrypted on this device. It is suitable for an owner-operated prototype, not yet a multi-user OAuth integration.
- The Android worker currently supports the listed safe built-in commands. It does not execute arbitrary scripts, containers, or application servers.
- The phone must be online and the worker service must remain allowed by Android. Battery optimisation, OS background limits, reboots, and connectivity can interrupt it.
- Because this prototype declares a `dataSync` foreground service and targets API 35, Android 15+ limits that service type to a total of 6 hours per app per rolling 24-hour period. TeleCloud now stops cleanly when Android reports that timeout; it cannot promise a continuously running 24/7 phone worker.
- This cannot promise unlimited storage, unlimited compute, or 24/7 availability. Free/open-source software does not remove hardware and platform limits.

These boundaries are intentional: the first milestone proves project isolation, local credential handling, and Telegram command transport without pretending a phone or Telegram is an always-on VPS.

## GitHub Actions setup

1. Create a fine-grained personal access token in GitHub and restrict it to the repository you want to control.
2. Grant **Actions: Read and write**. GitHub includes repository Metadata read access automatically.
3. In TeleCloud, open **GitHub**, paste the token, enter `owner/repo`, and set the branch used for manual workflow runs.
4. Tap **Connect** to list workflows and recent runs.
5. Tap **Run on branch** beside a workflow to send a `workflow_dispatch` event.

The selected workflow YAML must declare the `workflow_dispatch` trigger. GitHub accepting the dispatch request does not guarantee that the workflow will complete successfully. The token is encrypted with Android Keystore and can be removed with **Forget**.

## Telegram setup

1. Create a bot with [@BotFather](https://t.me/BotFather).
2. Send the bot a message such as `/start`.
3. Get your numeric chat ID using a trusted Telegram chat-ID helper, or from the bot update payload.
4. In TeleCloud, open **Telegram**, enter the bot token and your chat ID, then save.
5. Tap **Test bot connection**. A successful response should show the bot username.
6. Tap **Start worker**.
7. Send `/tc help` to the bot from the allowed chat.

Only the configured chat ID is allowed to issue commands. Keep the bot token private. Do not commit it to GitHub or share it in screenshots.

**Important:** Telegram's `getUpdates` long-polling mode cannot be used while the same bot has an active webhook or another process is consuming its updates. Use this bot for this worker, or remove its webhook before starting.

## Build the APK

The repository contains `.github/workflows/build-apk.yml`.

- Push to `main` or manually run **Build Android APK** in the GitHub Actions tab.
- Download the `telecloud-debug-apk` artifact from the completed workflow run.
- Extract `app-debug.apk` from the ZIP and install it on Android.

The APK is a debug-signed development build. Android may show a standard install warning for apps installed outside the Play Store. This is not a Play Store release.

## Tech stack

- Kotlin + Jetpack Compose
- Android Keystore (AES-GCM)
- GitHub REST API for workflow discovery, recent run history, and workflow dispatch
- Telegram Bot API (HTTPS)
- OkHttp
- GitHub Actions for APK builds

## Roadmap

1. Validate the Android APK and Telegram worker on a real device.
2. Add durable Telegram task records and safer task dispatch.
3. Add a real local API gateway that validates the generated project keys.
4. Add optional PC/Android worker pairing and worker health reporting.
5. Add supported runtimes for specific project workloads, with explicit resource and security limits.

## License

MIT. See [LICENSE](LICENSE).
