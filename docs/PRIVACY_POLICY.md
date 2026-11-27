# Privacy Policy — Zenith Personal & Family Finance

Effective Date: November 27, 2026
Developer: Arun Roshan (RoshZen) — [https://roshzen.in](https://roshzen.in)
Contact: [arunroshan1003@gmail.com](mailto:arunroshan1003@gmail.com)

---

## 1. Overview & Architecture
Zenith is an offline-first personal and family finance application for Android. Financial records created by you are stored locally on your device in an encrypted-at-rest Room SQLite database so core tracking, budgeting, and analytics operate without requiring constant network connectivity.

## 2. Information Zenith Accesses & How It Is Used

### 2.1 Financial Ledger Data
- **What you create:** Transactions, categories, budgets, savings goals, reminders, family budgets, and family ledger messages.
- **Storage:** Stored locally in Room SQLite (`cashflow_database`). When you choose to sign in with an account, your ledger synchronizes over HTTPS/TLS with Supabase PostgreSQL protected by per-user and per-family Row-Level Security (RLS) policies.
- **No Ad Tracking:** Zenith contains no advertising SDKs, third-party ad brokers, or behavioural ad-targeting profiles.

### 2.2 Notification Listener Access (`BIND_NOTIFICATION_LISTENER_SERVICE`)
- **Opt-In Only:** UPI and bank notification auto-tracking is off until you explicitly grant Android Notification Access and leave auto-detection enabled in **Settings → Auto-Track UPI Payments**.
- **On-Device Filtering:** `UpiNotificationListenerService` evaluates notifications strictly on-device against financial payment apps and transaction alert formats to extract the amount, merchant/title, payment rail, and transaction type.
- **Zero Non-Financial Harvesting:** Personal chat messages, emails, OTPs, and unrelated app notifications are ignored and are never stored or transmitted by the listener.

### 2.3 Camera & Receipt Scanning
- **On-Device OCR:** Scanning a receipt or QR code uses CameraX and Google ML Kit Text Recognition / Barcode Scanning locally on your device.
- **Family Invites:** Scanning a Family Invite QR decodes the invite code locally so you can verify the family name before joining.

### 2.4 Voice Entry & Gemini Parsing
- **User-Initiated Only:** Speech recognition runs only when you hold or trigger the Voice Entry button.
- **Cloud Structuring:** When natural-language voice or receipt parsing via the Gemini API is configured and triggered, only the spoken transcript or extracted receipt text you submit is sent to Google Gemini to structure the transaction fields (title, amount, category, type).

## 3. Data Export, Portability & Deletion
- **Full Ownership:** You can export your ledger anytime in PDF, Excel (`.xlsx`), CSV, or JSON backup formats from **Settings → Data & Privacy**.
- **Local Clear:** You can wipe cached local ledger data via **Clear Transactions & Ledger** or **Reset Device Account**.
- **Permanent Account & Cloud Deletion:** Signed-in users can permanently delete their authentication account and personal synced cloud data anytime inside **Settings → Account Actions → Delete Account & Cloud Data**, which executes the transactional `delete_my_account()` server routine and clears local session data.

## 4. Security Measures
- Encrypted HTTPS/TLS transport for Supabase authentication, real-time broadcasts, and cloud synchronization.
- Row-Level Security (RLS) isolating personal records per authenticated `user_id` and family records per verified `family_members` membership.
- Optional Biometric/PIN App Lock, balance masking on launch, and `FLAG_SECURE` screen-capture / recents privacy protection configurable inside Settings.

## 5. Contact
For privacy questions, support, or deletion inquiries, contact **Arun Roshan (RoshZen)** at [arunroshan1003@gmail.com](mailto:arunroshan1003@gmail.com) or via [https://roshzen.in](https://roshzen.in).
