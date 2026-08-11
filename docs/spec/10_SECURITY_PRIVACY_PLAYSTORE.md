# TripPulse --- Security, Privacy and Google Play Release

## 1. Threat model

Protect against:

-   trip credential guessing
-   trip enumeration
-   unauthorized live location access
-   leaked viewer links
-   replay of expired credentials
-   notification leakage
-   malicious event injection
-   abusive viewer access
-   accidental continuous tracking

## 2. Credentials

-   high entropy
-   never log raw secrets
-   secure verification
-   rate limit attempts
-   expire after trip
-   revoke on completion
-   no sequential trip IDs

## 3. Authorization

Every read/write must validate:

``` text
authenticated session
+
trip authorization
+
role
```

Viewer is read-only.

## 4. Database rules

Never trust the client to set:

-   authorization
-   server timestamps
-   viewer roles
-   trip ownership
-   expiry

Validate server-side.

## 5. Data minimization

Collect only what the product needs.

Location is sensitive.

Medicine notes are more sensitive.

Do not sell location data.

Do not use trip location for advertising.

## 6. Encryption

-   TLS in transit
-   Firebase-managed encryption at rest
-   sensitive local data protected using Android platform security where
    practical

## 7. Android permissions

Use minimum required scope.

Active trip tracking requires location access and foreground-service
handling consistent with Android platform requirements.

If background location permission is required, document and disclose the
core functionality clearly and complete Google Play's declaration
process.

## 8. Google Play

Before release verify current requirements for:

-   target SDK
-   foreground service declaration/type
-   location permission declarations
-   prominent disclosure
-   privacy policy
-   data safety form
-   account deletion requirements if accounts are introduced
-   notification permissions
-   sensitive permissions
-   Play Console review materials

The app's core purpose is private live journey sharing; this must be
clearly represented in store listing and in-app disclosures.

## 9. Privacy policy

Must explain:

-   location collection
-   when tracking starts
-   when tracking stops
-   who can view a trip
-   trip credential behavior
-   notes and sensitive data
-   SOS
-   retention
-   deletion
-   third-party services
-   support/contact mechanism

## 10. Logs

Never log:

-   trip secret
-   password
-   exact location unnecessarily
-   medicine text
-   private note content

## 11. App integrity

Use:

-   Firebase App Check where compatible
-   Play Integrity where justified
-   secure builds
-   signed release bundle
-   dependency vulnerability checks

## 12. Security testing

Before production:

-   brute-force testing
-   authorization bypass
-   expired-trip access
-   replay
-   malformed event injection
-   database rules testing
-   notification leakage
-   screenshot/clipboard review for secrets

## 13. Current official references

Google Play's current location policy requires background location to be
core functionality and may require a declaration, prominent disclosure,
privacy policy and supporting review material. Android 14+ foreground
services require correct service types/permissions.

Use official documentation as the final authority because Play/Android
requirements can change.

### Official references consulted

-   Google Play background location policy:
    https://support.google.com/googleplay/android-developer/answer/9799150
-   Google Play foreground location guidance:
    https://support.google.com/googleplay/android-developer/answer/17033915
-   Android foreground service launch requirements:
    https://developer.android.com/develop/background-work/services/fgs/launch
-   Firebase Realtime Database Android offline behavior:
    https://firebase.google.com/docs/database/android/offline-capabilities
-   Firebase Realtime Database Android read/write and offline behavior:
    https://firebase.google.com/docs/database/android/read-and-write

These references were checked in August 2026; verify them again
immediately before Play submission.
