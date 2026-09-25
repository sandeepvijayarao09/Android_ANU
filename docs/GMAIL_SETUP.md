# Connecting Gmail (one-time setup for the `personal` build)

Anu reaches Gmail through Google Play services (ARCHITECTURE §9, ADR-029).
- No client ID or secret ships in the app. Google recognizes Anu by its **package name and signing certificate**, which you register once in Google Cloud.
- Anu never sees your password and stores no tokens.

You do this setup yourself, because it's your Google account. It takes about 10 minutes.

## 1. Create a Google Cloud project and turn on the Gmail API

1. Open <https://console.cloud.google.com/> and create a project, for example "Anu personal".
2. Go to **APIs & Services → Library**, search for **Gmail API**, and click **Enable**.

## 2. Set up the consent screen in testing mode

1. Go to **Google Auth Platform** (formerly the OAuth consent screen).
2. Choose **External** as the user type.
3. Fill in the app name ("Anu") and your email.
4. Under **Audience**, keep the publishing status as **Testing**.
5. Add **your own Gmail address** as a test user.
6. Under **Data access**, add these scopes:
   - `https://www.googleapis.com/auth/gmail.readonly`, to search and read;
   - `https://www.googleapis.com/auth/gmail.compose`, to save drafts;
   - `https://www.googleapis.com/auth/gmail.send`, to send, always after your approval in Anu.

   Google marks the first two as restricted. That's fine in testing mode, which allows up to 100 test users with no verification.

## 3. Create the Android OAuth client

1. Go to **Credentials → Create credentials → OAuth client ID**.
2. Set **Application type** to **Android**.
3. Set **Package name** to `io.github.sandeepvijayarao09.anu.personal`.
4. Set **SHA-1 certificate fingerprint** (your debug keystore, `~/.android/debug.keystore`) to:

   ```
   68:EC:02:57:4F:07:3E:CC:AE:23:01:38:73:BA:7B:05:FE:9C:9A:9B
   ```

5. Click **Create**. There's nothing to copy into Anu.

A release build or the `play` flavor needs its own client: package `io.github.sandeepvijayarao09.anu` and the release key's SHA-1.

## 4. Connect

1. On the phone, or on the `anu_api36` emulator, add your Google account yourself under **Settings → Passwords & accounts**.
2. In Anu, go to **Settings → Gmail → Connect**, pick your account, and allow access.

Settings then shows **Connected**. Try, for example, "Anything from Maya in my inbox this week?"

## Troubleshooting

| What you see | Likely cause |
|---|---|
| "Gmail couldn't connect: 10" (`DEVELOPER_ERROR`) | The package name or SHA-1 doesn't match the Android client, or the Gmail API isn't enabled |
| The consent screen says "Access blocked" | Your account isn't a test user |
| Connected, but searches fail with 403 | A scope is missing from the consent screen |
| Gmail asks you to reconnect after about a week | Google limits how long testing-mode grants for restricted scopes last. Reconnect in Settings |

## What Anu does with Gmail

- **Reads** (search, threads) run without asking. The content is treated as untrusted: after reading email, any change Anu wants to make needs your approval.
- **Drafts** stay in your mailbox. Once untrusted content has been read, they also need approval.
- **Sending always shows you the exact message:** recipients, subject and body. Nothing is sent until you approve it.
- **Everything Anu sends to Gmail is recorded in Settings → Privacy Ledger:** queries, drafts and sends, as sizes and purposes, never the content.
