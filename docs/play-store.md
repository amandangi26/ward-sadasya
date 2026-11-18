# Google Play: what to fill in for INC MCD NETWORK

Everything below goes in Play Console under **Policy and programs > App content**. The answers match what the app (v2.3) really does. If the app changes what it collects, change these answers too.

## Links to paste

| Field | Link |
|---|---|
| Privacy policy | https://congressdelhinetwork.com/privacy |
| Account deletion (Data safety form asks for this) | https://congressdelhinetwork.com/delete-account |

The app links to the same page from inside: "Request for Account Deletion" at the bottom of the menu and on the login screen. Requests land in the admin panel under **People > Account deletion**. Google's rule is that the request must be acted on, so check that tab regularly.

## Data safety form

**Does your app collect or share any of the required user data types?** Yes.
**Is all of the user data collected by your app encrypted in transit?** Yes (HTTPS).
**Do you provide a way for users to request that their data is deleted?** Yes. Paste the delete-account link.

Data types to tick. For every one: **Collected: yes. Shared with third parties: no.** Processing is not "ephemeral".

| Category | Data type | Required or optional | Purpose to tick |
|---|---|---|---|
| Personal info | Name | Required | App functionality, Account management |
| Personal info | Phone number | Required | App functionality, Account management |
| Personal info | Other info (date of birth, gender, marital status and anniversary, family size, party post, ward and area, links to own social media accounts) | Optional (the user fills the profile) | App functionality |
| Photos and videos | Photos | Optional | App functionality |
| Photos and videos | Videos | Optional | App functionality |
| Audio files | Voice or sound recordings | Optional | App functionality |
| Messages | Other in-app messages | Optional | App functionality |
| App activity | App interactions (tasks done, posts shared, points, calls started from the app and their result) | Required | App functionality, Analytics |
| App info and performance | none | | |
| Device or other IDs | Device or other IDs (Firebase notification token) | Required | App functionality |

Do **not** tick: location (the app no longer collects it, from v2.3), financial info, health and fitness, emails or SMS, contacts, calendar, web browsing, files and docs, installed apps.

Notes for whoever fills it:

- "Shared" in Google's meaning is passing data to another company for its own use. Supabase (database and storage) and Firebase (notifications) are service providers working for us, which Google does not count as sharing.
- Leaders in the party hierarchy seeing a worker's name and number is part of the app's own function, inside the same organisation. It is described in the privacy policy.
- The app has no ads and no advertising ID. Answer "No" to the advertising ID question.

## Government apps declaration

Google asks whether the app is made by or on behalf of a government. A political party is not a government body, and the app does not offer any government service or claim to. The straightforward answer is **No**.

Two things to keep in mind:

- The store listing and screenshots must not look like a government app. Say clearly that it is the Delhi Pradesh Congress Committee's internal app for its own workers.
- Google sometimes asks for proof when an app carries a party's name and symbol. Keep a letter on DPCC letterhead, signed by an office bearer, saying that Coceptual Media builds and runs INC MCD NETWORK for DPCC and may publish it under its developer account. Upload it if Google asks, or attach it in the "App access" notes.

## Other App content sections

| Section | Answer |
|---|---|
| App access | Restricted: only numbers added by the DPCC office can log in. Give Google a test mobile number that is in the member list (and one leader number if you want them to see that side), and say there is no OTP or password: enter the number and press आगे बढ़ें. |
| Ads | No ads |
| Content rating | Fill the questionnaire: no violence, no sexual content, no gambling; users can send messages and upload photos and videos, so answer "yes" to user-generated content and say it is moderated by the office |
| Target audience | 18 and over |
| News app | No |
| Health apps | No health features |
| Financial features | None |

## Permissions the app asks for

| Permission | Why |
|---|---|
| Notifications | Task reminders and messages |
| Microphone | Recording a voice note, only when the record button is pressed |
| Storage (Android 9 and below only) | Saving a poster the user chose to download |

Camera and gallery are opened through Android's own picker, so no camera or media permission is requested.

## Before submitting

1. In admin **Settings**, set the app version code to 15 and the version name to 2.4, so older installs are told to update.
2. Play needs a signed app bundle (.aab). The build workflow now makes one next to the APK; it is in the GitHub release.
3. Organisation accounts are needed for some app types. If the Play account is a personal one and Google objects, the fix is an organisation account in the name of Coceptual Media or DPCC.
