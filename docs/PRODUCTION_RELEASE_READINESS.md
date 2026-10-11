# PitTech production release readiness

Audit date: October 10, 2026

## What is prepared

- The Android app uses package com.pittech, compileSdk 37, and targetSdk 36. Google Play's current target API policy requires API 36 for new app and update submissions from August 31, 2026. The build meets that requirement.
- Android checks passed on main commit 401a2b7f19c2684b4e0aa1e760938b40ecaf8444. The API 36 emulator matrix is required; API 37 remains advisory in the workflow because its emulator graphics stack is unstable.
- A Play upload to Alpha succeeded on October 2, 2026, including the existing upload key, service-account authentication, Play Developer API enablement, and publication. This confirms the secrets worked for Alpha then; it does not prove production-track permission or that the credentials have not changed.
- The privacy page and its health endpoint returned HTTP 200. The public app-ads.txt file also returned HTTP 200 with publisher ID pub-2708638041809482, matching the AdMob unit.
- The feedback relay health endpoint returned HTTP 200 and reported GitHub configured for michaelhmiv/PitTech. Railway shows its current production deployment successful.
- The Play release workflow is manually triggered from main. Alpha remains the default and uses test ads. Production requires explicit confirmation, builds with live ads, and starts a staged rollout at 5%, 10%, 25%, or 50% of users; 10% is the default. It does not complete a 100% rollout automatically.

The new native ad placement is the first row in the Cooks list whenever UMP permits ad requests. Its card matches the cook rows' shape, surface color, and inset. AdMob's native ad attribution remains visible. An actual ad can still be unavailable when consent is declined, the device is offline, the ad unit returns no fill, or AdMob has not approved the app. AdMob review can follow the first public Play listing, so do not expect full ad serving until the app-ads.txt and readiness checks are approved.

## Checks required before production

1. Merge the intended app changes to main, then confirm the Android checks on the current main commit are green. The Play release workflow always checks out main. The repository currently has no branch protection or ruleset enforcing those checks.
2. Confirm the intended feature set is on main. Open pull requests #7 and #8 are not part of main; the release action will not include them unless they are merged first.
3. In Play Console, confirm production access, store listing, app content, content rating, target audience, Ads declaration, Data safety form, privacy-policy URL, and release notes are complete. If this is a personal developer account created after November 13, 2023, Google requires at least 12 closed-testers to remain opted in continuously for 14 days before applying for production access. Confirm the account type and tester history in Play Console.
4. Confirm the service account behind GCP_SERVICE_ACCOUNT_JSON has permission to publish to the production track. The successful Alpha run only verifies testing-track access. Confirm that the same registered upload key is still represented by the four ANDROID_KEYSTORE secrets.
5. In AdMob, configure UMP messages and verify the app's consent flow. The repository previously recorded the app as unpublished and its UMP messages as unconfigured; the AdMob console was not available during this audit. Link the app to the public Play listing and verify app-ads.txt in AdMob. App-readiness approval is needed for full ad serving but may follow first publication; until approved, live ads can be limited or absent.
6. In GitHub Actions, choose Play release, select production, leave or set the staged rollout fraction, and check the production-readiness confirmation only after steps 1–4 are complete and UMP is configured. Review the signed-bundle artifact and Play Console rollout after the run; increase the rollout in Play Console after monitoring. Do not treat the ad slot as a guarantee of an impression.

The Play workflow reuses the existing play-closed-testing.yml path so Alpha and production releases continue sharing one monotonic GitHub Actions run counter for versionCode. Keep this path fixed unless the version-code scheme is deliberately migrated.

## Railway state

The feedback relay is deployed from main and healthy. The privacy service is reachable and its page, health endpoint, and app-ads.txt returned HTTP 200 during this audit. Railway has one pending production change that switches the privacy service's source branch from codex/pittech-release-policy-railway to main. It is staged but not live. Review and apply it in Railway if that source switch is still intended before launch.

## References

- [Google Play target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878)
- [Production access requirements for new personal developer accounts](https://support.google.com/googleplay/android-developer/answer/14151465)
- [AdMob setup and app readiness](https://support.google.com/admob/answer/15948559)
- [AdMob app-ads.txt verification](https://support.google.com/admob/answer/14538460)
