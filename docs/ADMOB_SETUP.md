# AdMob setup

Audited October 10, 2026. The AdMob console was not available to inspect during this audit; confirm its current app, consent, and readiness status before a production release.

| Item | Value |
| --- | --- |
| Package | com.pittech |
| AdMob app ID | ca-app-pub-2708638041809482~5194145527 |
| Native ad unit | ca-app-pub-2708638041809482/6602595967 |
| Google native test unit used by default | ca-app-pub-3940256099942544/2247696110 |

The app uses the GMA Next-Gen SDK and the User Messaging Platform (UMP). UMP refreshes consent information when the app opens, presents any message configured for the AdMob app, and gates ad requests on canRequestAds(). Settings exposes Privacy options when UMP reports that it is required.

When consent permits ad requests, one native ad is loaded as the first item in the Cooks list on each visit. The row shares the cook cards' surface color, 14 dp corners, and 18 dp inset. Its Ad attribution remains clearly visible. An ad is not guaranteed when consent, connectivity, ad fill, or AdMob app approval is unavailable. Native ads do not appear on Start Cook, cook-detail, Insights, Devices, or Settings screens. The loaded ad is destroyed when the Cooks screen leaves the composition.

The publisher file is privacy-site/app-ads.txt. It was also checked on the live privacy site on October 10, 2026: the public app-ads.txt URL returned HTTP 200 with the publisher ID matching the native ad unit. AdMob must still verify the file using the developer website URL in the Play listing. Confirm that status in AdMob.

## Test and release behavior

Local and pull-request builds use Google's test unit. Debug builds always use the test unit, even if pittechLiveAds=true is passed. Alpha closed testing also uses the test unit. The production option in the Play release workflow builds with PitTech's live unit and starts a staged rollout after explicit readiness confirmation.

Before that production option is used, confirm in Play Console and AdMob that:

- The public store listing is available and the app is linked as published in AdMob.
- The developer website in the Play listing serves the app-ads.txt file and AdMob shows it as verified.
- UMP privacy and consent messages are configured for the regions where the app will be distributed.
- The Play Ads declaration and Data safety form match the app's SDK behavior and privacy policy.

For ads to serve fully from launch, also confirm that the published app is approved after AdMob's app-readiness review and app-ads.txt is verified. AdMob can limit or delay serving while those checks are pending; an ad is never guaranteed on every visit. The repository previously recorded the AdMob app as unpublished and UMP messages as unconfigured. Treat those as unresolved until verified in the consoles; repository code cannot establish current account state.
