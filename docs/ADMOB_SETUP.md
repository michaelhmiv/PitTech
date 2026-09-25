# AdMob setup

PitTech has one AdMob Android app, registered as unpublished while the app is in closed testing.

| Item | Value |
| --- | --- |
| Package | `com.pittech` |
| AdMob app ID | `ca-app-pub-2708638041809482~5194145527` |
| Native ad unit | `ca-app-pub-2708638041809482/6602595967` |
| Google native test unit used by default | `ca-app-pub-3940256099942544/2247696110` |

The app uses GMA Next-Gen SDK and UMP. UMP refreshes consent information when the app opens, presents any message configured for the AdMob app, and gates ad requests on `canRequestAds()`. Settings exposes the Privacy options entry only when UMP reports it is required.

The native placement appears after the fourth completed cook, and only when there are no active cooks. It does not appear on the Start Cook, active-cook, Insights, Devices, or Settings screens. The app displays one native ad per visit to that history view and destroys the loaded ad when the view leaves the composition.

## Test and release behavior

All local and CI builds use Google's native test ad unit by default. The Play closed-testing workflow explicitly passes `-PpittechLiveAds=false`, so closed testers will not receive live ads. To build with PitTech's live native unit later, pass `-PpittechLiveAds=true` only after the privacy policy and Play disclosures have been updated and the public store listing, app-ads.txt, and AdMob app-readiness review are complete.

The AdMob app is currently unpublished and has not been linked to a public store listing. The native unit may take up to an hour to begin returning ads. UMP consent messages still need to be configured in AdMob's Privacy & messaging area before broad distribution.
