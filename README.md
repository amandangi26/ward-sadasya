# INC Delhi (Delhi Pradesh Congress Committee)

[![Latest Release](https://img.shields.io/badge/version-v2.5%20(Build%2016)-blue.svg)](https://github.com/amandangi26/ward-sadasya/releases/latest)
[![Package Name](https://img.shields.io/badge/package-com.coceptual.delhicongress-1B4FA8.svg)]()
 [![Platform](https://img.shields.io/badge/platform-Android%20%7C%20Web%20Admin-green.svg)]()
[![Google Play](https://img.shields.io/badge/Play%20Store-Ready%20(.AAB)-34A853.svg)](https://github.com/amandangi26/ward-sadasya/releases/latest)
[![Backend](https://img.shields.io/badge/backend-Supabase%20Edge%20Functions-3ECF8E.svg)]()
[![Portal](https://img.shields.io/badge/portal-congressdelhinetwork.com-1B4FA8.svg)](https://congressdelhinetwork.com/mcd)

**INC Delhi** is the official digital operations and campaign management platform of the Delhi Pradesh Congress Committee (DPCC). It connects, mobilizes, and evaluates organizational workers, block presidents, assembly observers, and district executives across all **250 MCD wards**, 70 Delhi Assembly constituencies, and 14 organizational districts.

---

## 📌 Important Links

| Service / Page | Live URL | Description |
| :--- | :--- | :--- |
| **Admin & Telecalling Portal** | [congressdelhinetwork.com/mcd](https://congressdelhinetwork.com/mcd) | Command center for analytics, task creation, calling, and member verification |
| **Fallback Admin Portal** | [ward-sadasya.vercel.app/mcd](https://ward-sadasya.vercel.app/mcd) | Vercel production backup mirror |
| **App Download (APK & AAB)** | [congressdelhinetwork.com/download](https://congressdelhinetwork.com/download) | Worker download landing page with auto-updates |
| **Privacy Policy** | [congressdelhinetwork.com/privacy](https://congressdelhinetwork.com/privacy) | DPDP Act 2023 & Google Play compliant privacy policy |
| **Terms of Service** | [congressdelhinetwork.com/terms](https://congressdelhinetwork.com/terms) | Google Play compliant Terms of Service & usage agreement |
| **Account Deletion Request** | [congressdelhinetwork.com/delete-account](https://congressdelhinetwork.com/delete-account) | Mandatory Google Play user data & account deletion mechanism |
| **Latest Releases** | [GitHub Releases Latest](https://github.com/amandangi26/ward-sadasya/releases/latest) | Direct downloads for `.aab` (Google Play) and `.apk` |

---

## 🏛️ System Architecture

The platform consists of three core components built around a centralized PostgreSQL backend:

```
ward-sadasya/
├── android/               # Native Android App (Kotlin + Jetpack Compose)
│   ├── app/src/main/java/com/coceptual/delhicongress/
│   │   ├── MainActivity.kt   # UI, navigation, and all member screens
│   │   ├── Leader.kt         # Leadership dashboards and field reports
│   │   ├── Api.kt            # Authenticated API client and session management
│   │   ├── Media.kt          # Multi-photo, video, and audio note recorders
│   │   ├── Push.kt           # Firebase Cloud Messaging (FCM) push handler
│   │   └── ReminderWorker.kt # Background task check-ins and notifications
│   └── app/build.gradle.kts  # Package: com.coceptual.delhicongress
├── admin/                 # Web Admin & Telecaller Portal (React 18 + Vite)
│   ├── src/                  # Analytics, Tasks, Calling Panel, Members, Reports
│   └── public/               # download.html, privacy.html, delete-account.html
├── supabase/              # Backend & Database
│   ├── schema.sql            # Core database schema with Row Level Security (RLS)
│   ├── migration_v2_1_v2_2.sql # Schema migration for v2.1/v2.2
│   ├── migration_v2_3.sql    # Schema migration for v2.3
│   └── functions/            # Deno Edge Functions (app, leader, admin, push)
└── docs/
    └── play-store.md         # Play Console submission guide & Data Safety answers
```

---

## 📱 Google Play Store Technical Handover

Essential technical specifications for Google Play Console submission:

| Parameter | Value | Details |
| :--- | :--- | :--- |
| **App Name** | `INC MCD NETWORK` | Official app title (max 30 characters) |
| **Package Name (Application ID)** | `com.coceptual.delhicongress` | Unique identifier on Google Play |
| **Version Name** | `2.5` | User-facing release version |
| **Version Code** | `16` | Build increment integer |
| **Target SDK** | `36` (Android 16) | Compliant with Google Play's latest requirements |
| **Minimum SDK** | `24` (Android 7.0) | Supports 98%+ of active Android devices |
| **Production Bundle** | `app-release.aab` | Android App Bundle uploaded to Google Play |
| **Direct APK** | `app-release.apk` | Standalone signed APK for direct installation |
| **Keystore Alias** | `wardsadasya` | Release keystore alias |
| **Keystore File** | `android/app/release.keystore` | Permanent RSA 2048-bit keystore |
| **Declared Permissions** | `INTERNET`, `POST_NOTIFICATIONS`, `RECORD_AUDIO` | *Note: Location permissions have been completely removed* |
| **App Access (Demo Login)** | Restricted (No OTP) | Enter registered 10-digit number and tap "आगे बढ़ें" |

---

## 🌟 Key Features (v2.3)

### 1. Six Leadership Levels in One Unified App
The app automatically detects the user's role and designated area upon mobile number entry:

1. **DPCC President (माननीय अध्यक्ष जी):**
   * Real-time Delhi-wide, district, and block level performance analytics.
   * Direct task assignment with voice notes to General Secretaries and District Presidents.
   * Review of worker messages in 7-day bundles with topic and tone insights.
2. **General Secretary (महासचिव):**
   * Daily coordination monitoring and oversight of District Coordinators (DC).
   * Multi-level task distribution across five organizational tiers.
3. **District Coordinator (ज़िला समन्वयक - DC):**
   * Dedicated calling lists for assigned organizational districts.
   * Direct daily reporting to the General Secretary on call outcomes and outreach.
4. **District President (ज़िला अध्यक्ष - DP):**
   * District ranking, live task completion score, and district-wide broadcasts.
   * Oversight of Assembly Observers and local block leaders.
5. **Assembly Observer (विधानसभा प्रेक्षक):**
   * Ground visit reporting with activity checklist, photos, videos, and voice notes.
   * Monthly evaluations and qualitative feedback on ward members.
6. **Block President & Ward Member (ब्लॉक अध्यक्ष एवं वार्ड कार्यकर्ता):**
   * Complete daily campaign tasks (photo/video upload, social share, checklist, text).
   * Receive leadership assignments and personal inbox notifications.
   * Submit self-initiated grassroots tasks (Own Tasks) for points.

### 2. Multi-Profile Switching
* Workers holding multiple responsibilities (e.g., Ward Member + Block President or Observer) can seamlessly switch profiles from the slide-in menu **without logging out**.

### 3. Unified Task Funnel & New Task Types
v2.3 expands beyond photo/video uploads into an integrated task funnel:
* **Upload Tasks:** Submit proof of ground activities with photos and videos.
* **Share Tasks:** Step-by-step 1-tap sharing to WhatsApp, Facebook, X, and Instagram.
* **Checklist Tasks (Choice):** Complete verifiable field checklists with multiple checkboxes.
* **Text / Voice Responses:** Submit qualitative reports, feedback, or voice notes.
* Tasks issued by senior leadership appear directly in the member's daily home screen.

### 4. Transparent 100-Point Reward System
* **Social Share:** +100 points per task per social platform.
* **Task Completion:** +100 points for completing a daily task (upload, checklist, or text).
* **Own Tasks:** +100 points upon admin approval for self-initiated campaign work.

---

## 💻 Web Admin & Calling Portal (`/mcd`)

* **Live Analytics & Heatmap:** Color-coded 250-ward interactive map, completion trends, and hourly upload tracking.
* **Task Management:** Create, schedule, and filter tasks by audience (all members, ward members only, block presidents only) and geography.
* **Telecalling Console:** District-filtered worker calling interface with 1-click dial/WhatsApp and outcome tracking.
* **Data Discrepancies:** Detect and resolve unmapped wards, duplicate profiles, and missing contacts in one click.
* **Account Deletion Requests:** Process Google Play compliance deletion requests with automated cascading data and storage cleanup.
* **Leader & Profile Approvals:** Verify and approve profile updates and credentials submitted by workers.

---

## 🛠️ Local Development & Deployment

### 1. Supabase Backend
1. Link to the remote Supabase project:
   ```bash
   supabase link --project-ref xqbjqfjystiakfuwplby
   ```
2. Run database migrations:
   ```bash
   supabase db query --remote --yes -f supabase/migration_v2_3.sql
   ```
3. Deploy Edge Functions:
   ```bash
   supabase functions deploy app --no-verify-jwt --yes
   supabase functions deploy leader --no-verify-jwt --yes
   supabase functions deploy admin --no-verify-jwt --yes
   supabase functions deploy push --no-verify-jwt --yes
   ```

### 2. Admin Portal Setup & Build
```bash
cd admin
npm install
npm run build
```

### 3. Android Build (APK & AAB)
* **Continuous Integration (GitHub Actions):** Pushing to `main` automatically triggers `.github/workflows/android-apk.yml`, building and signing both `.apk` and `.aab` artifacts.
* **Local Terminal Build:**
  ```bash
  cd android
  ./gradlew assembleRelease bundleRelease
  ```
  - **APK Output:** `android/app/build/outputs/apk/release/app-release.apk`
  - **AAB Output:** `android/app/build/outputs/bundle/release/app-release.aab`

---

## 📜 Version History & Changelog

* **v2.5 (Build 16) - Latest:**
  * Enabled user uploads up to 500 MB with high-performance on-device video (hardware H.264/AAC) and image compression.
  * Direct Presigned Storage Upload architecture eliminates main server compute and bandwidth overhead.
  * Added graceful offline media archive UI states in Admin Panel for backed-up assets.
* **v2.4 (Build 15):**
  * Updated targetSdk and compileSdk to API 36 (Android 16) for latest Google Play release standards.
  * Implemented `enableEdgeToEdge()` for safe system bar rendering across Android 15/16 devices.
  * Upgraded Gradle to 8.11.1 and Android Gradle Plugin to 8.9.1.
* **v2.3 (Build 14):**
  * Added Checklist (`choice`) and Written/Spoken (`text`) task types.
  * Implemented Unified Task Funnel (leadership assignments integrated into daily tasks).
  * Removed location tracking and permissions for streamlined Google Play Store approval.
  * Enabled remarks and voice notes for leader task completions.
  * Deployed DPDP Act 2023 compliant privacy policy and automated account deletion flow.
* **v2.2 (Build 13):**
  * Updated application ID and package name to `com.coceptual.delhicongress`.
  * Added automated Google Play Android App Bundle (`.aab`) CI/CD pipeline.
  * Resolved menu, feedback headers, and navigation bugs.
* **v2.1 (Build 12):**
  * Introduced Block President and District Coordinator (DC) leadership tiers.
  * Implemented multi-profile switching within a single authenticated session.
  * Added step-by-step social media sharing flow.
  * Introduced monthly observer feedback reports and coordinator call logging.
* **v2.0 (Build 11):**
  * Restructured 4-tier Delhi master data hierarchy (Lok Sabha > District > Assembly > 250 Wards).
  * Introduced Data Discrepancies resolution panel in the admin dashboard.
  * Added media removal tools and topic/sentiment analysis for messages sent to the DPCC President.
* **v1.8 - v1.9:**
  * Standardized 100-point reward structure, slide-in navigation, and searchable area pickers.

---

**© 2026 Delhi Pradesh Congress Committee (DPCC) · Technology Partner: Coceptual Media**
