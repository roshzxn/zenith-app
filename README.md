# Zenith — Personal Expense & UPI Finance Tracker

Zenith is a modern Android personal finance and expense tracking application (`com.zenith.app`) built with **Kotlin**, **Jetpack Compose (Material 3)**, **Room**, **Supabase**, **CameraX + ML Kit**, and **Gemini AI**. It combines an offline-first personal ledger with direct UPI app redirection, on-device UPI payment auto-detection, receipt OCR, voice expense logging, budgets, savings goals, and multi-format reporting.

---

## Key Features

### Personal Ledger & Activity Explorer
- **Home Dashboard**: Live net balance ticker with rolling digit odometer (`RollingCounterText`), income/spent cards, privacy balance masking, and quick entry shortcuts (`Scan QR`, `UPI Pay`, `Receipt`, `Voice Entry`).
- **Unified Activity Feed**: Single smooth-scrolling feed with instant keyword search, transaction type toggles, date-range filters (`All Time`, `Today`, `This Week`, `This Month`), category chips, daily grouped cards, detailed modal editor (`TransactionDetailModal`), and Undo delete support.

### UPI Payments & On-Device Auto-Tracking
- **Redirect & Note Workflow (`UPIPaySheet`, `UpiPaymentFlow`)**: Launch any installed UPI app (**Google Pay, PhonePe, Paytm, BHIM, CRED, Amazon Pay, WhatsApp Pay, MobiKwik, Navi, Freecharge, super.money**), automatically copy recipient UPI ID/mobile details to the clipboard, and prompt with pre-filled confirmation on return.
- **Multi-Format QR Scanning (`UpiQrScanModal`)**: Scans **standard `upi://pay` links**, **EMVCo / BharatQR** soundbox codes, and **web-wrapped UPI URLs** via CameraX + ML Kit while preserving merchant metadata (`mc`, `tr`, `tn`, `mode`, `orgid`) and raw cryptographic signatures (`sign=`).
- **On-Device Notification Listener (`UpiNotificationListenerService`)**: Parses debit/credit alerts 100% on-device across major Indian UPI apps and banking apps to extract amount, payee name, VPA, and 12-digit UTR with 1-tap notification shade logging and automatic deduplication (`UpiPaymentBus`, `NotificationExpenseActionReceiver`).
- **Strict State & Security Validation (`UpiService`)**: Separates `SUCCESSFUL`, `PENDING`, `FAILED` (including bank/PSP security declines), `CANCELLED`, and `INITIATED` outcomes so declined, failed, or cancelled attempts cannot be saved as settled expenses. Includes a [`UpiGatewayVerifier`](app/src/main/java/com/zenith/app/data/upi/UpiService.kt) contract so gateway orders require authenticated server-side / webhook verification before settlement.

### AI & Smart Automation
- **Voice Expense Assistant (`VoiceAiModal`)**: Speak natural commands (e.g., *"Spent 250 on biryani via UPI"*) processed via speech recognition and `GeminiAiService`.
- **Receipt Scanner (`ReceiptScanModal`)**: Capture or pick receipt images for on-device ML Kit OCR + itemized extraction (`ScannedItemEntity`) attached directly to transactions.
- **Financial Coach (`AnalyticsScreen`)**: Period comparisons (`This Month`, `7 Days`, `30 Days`, `All Time`), Income vs. Expenses flow chart, category breakdown donut chart, payment method breakdown, and AI-powered or local data-driven insights.

### Planning, Security & Backups
- **Budgets, Savings Goals & Auto-Pay (`BudgetsAndGoalsScreen`)**: Monthly/weekly/custom category limits, progress tracking, goal fund deposits, and recurring bill/subscription reminders (`AutoPayReminderDao`).
- **Adaptive 3-Mode Theme**: **Crisp Light**, **Obsidian Navy Dark**, and **OLED Pure Black (`#000000`)** with accessible contrast tokens (`Color.kt`, `Theme.kt`).
- **Cloud Sync & Offline Vault**: Full guest offline mode via Room (`v16`), plus Supabase Auth, Postgrest sync, and Realtime updates (`SyncEngine`, `supabase/schema.sql`).
- **Data Export & Restore (`ExportImportService`)**: Export filtered reports to **PDF, Excel, CSV, or JSON**, import statements, and restore full JSON vault snapshots (`BackupRestoreModal`).
- **Biometric / PIN App Lock**: Optional device authentication gate (`USE_BIOMETRIC`).

---

## Tech Stack

| Layer | Technology |
|---|---|
| **Language & Build** | Kotlin `2.2.10`, Android Gradle Plugin `9.1.1`, KSP `2.3.5` |
| **UI** | Jetpack Compose (`BOM 2024.09.00`), Material 3, Navigation Compose |
| **Local Storage** | Room `2.7.0` (`CashFlowDatabase`), SharedPreferences |
| **Cloud Backend** | Supabase (`postgrest-kt`, `auth-kt`, `realtime-kt` `3.0.1`), Ktor `3.0.0` |
| **Camera & ML** | AndroidX CameraX `1.5.0`, ML Kit Barcode Scanning `17.3.0` & Text Recognition `16.0.1` |
| **AI Integration** | Gemini API (`GeminiAiService`) |
| **Testing** | JUnit 4, Robolectric `4.16.1`, Compose UI Test, Kotlinx Coroutines Test `1.10.2` |

- **Android SDK**: `minSdk = 24`, `targetSdk = 36`, `compileSdk = 36`

---

## Project Structure

```text
zenith-app/
├── app/src/main/
│   ├── AndroidManifest.xml                  # Permissions, <queries> UPI visibility, services & receivers
│   └── java/com/zenith/app/
│       ├── MainActivity.kt                  # App shell, navigation, auth lock, modals
│       ├── ZenithApplication.kt
│       ├── data/
│       │   ├── ai/GeminiAiService.kt        # Voice, OCR & financial coaching prompts
│       │   ├── dao/                         # Room DAOs (CashFlowDao, AutoPayReminderDao, UserProfileDao)
│       │   ├── database/CashFlowDatabase.kt # Room v16 schema, migrations & category seeding
│       │   ├── export/ExportImportService.kt# PDF, Excel, CSV & JSON import/export engine
│       │   ├── models/                      # TransactionEntity, BudgetEntity, SavingsGoalEntity, etc.
│       │   ├── network/                     # SupabaseAuthService, SyncEngine, SessionManager
│       │   ├── repository/                  # CashFlowRepository, UserProfileRepository
│       │   ├── upi/                         # UpiService, UpiPaymentBus, NotificationListener & Parser
│       │   └── util/                        # CategoryIconHelper, DateTimeUtils
│       └── ui/
│           ├── components/                  # UPIPaySheet, UpiPaymentFlow, UpiQrScanModal, Modals, Cards
│           ├── screens/                     # Home, Transactions (Activity), Budgets, Analytics, Profile, Auth
│           ├── theme/                       # Color tokens, 3-mode ZenithTheme, Typography
│           └── viewmodel/CashFlowViewModel.kt
├── app/src/test/java/com/zenith/app/        # Unit & Robolectric Compose test suites
├── supabase/schema.sql                      # PostgreSQL schema, triggers, RLS policies & realtime config
├── build.gradle.kts / settings.gradle.kts
└── .env.example                             # Environment variables template
```

---

## Getting Started

### 1. Prerequisites
- Android Studio (supporting AGP `9.1.1` / Kotlin `2.2.10`) or CLI with **JDK 21** and **Android SDK 36**.

### 2. Configure Environment Secrets
Create a `.env` file in the project root from `.env.example` (never commit `.env`):

```properties
GEMINI_API_KEY=your_api_key_here
SUPABASE_URL=https://your-project-id.supabase.co
SUPABASE_ANON_KEY=your_supabase_anon_key_here
```

- Zenith also supports **Offline Vault Mode** without Supabase credentials, and falls back gracefully on-device if cloud or AI keys are absent.

### 3. Configure Supabase Backend (Optional)
To enable cloud sync across sessions:
1. Create a Supabase project.
2. Execute [`supabase/schema.sql`](supabase/schema.sql) in the Supabase SQL Editor to create tables, columns, triggers, strict owner-isolated RLS policies, and Realtime publications.

### 4. Build & Run Tests

```bash
# Run all local JVM & Robolectric Compose unit tests
./gradlew testDebugUnitTest

# Build debug APK
./gradlew assembleDebug
```

---

## Payment Privacy & NPCI Compliance Notes

- Zenith **never requests, collects, or stores UPI PINs, OTPs, or banking passwords**.
- All UPI notification parsing runs **100% on-device**; non-transactional notifications (OTPs, login alerts, promotional messages) are ignored immediately.
- Under NPCI / PSP intent security policies in India, third-party unsigned P2P `upi://pay` intents can be declined by consumer UPI apps (such as Google Pay or PhonePe). Zenith prevents dead ends by offering a **1-tap direct UPI app redirect + clipboard payee copy** alongside Notification Access auto-tracking, while validating returned response codes before allowing any ledger entry.
