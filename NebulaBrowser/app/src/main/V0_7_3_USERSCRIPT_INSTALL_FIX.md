# V0.7.3 Userscript Auto-Install Fix

Based on NebulaBrowser V0.7.2 Media3 HD Player.

## Fixed
- Clicking `.user.js` / `.userjs` install links no longer navigates to raw JavaScript.
- Added a native `NebulaUserscriptInstall` bridge for Greasy Fork, Sleazy Fork, OpenUserJS and compatible repositories.
- Added page-side click interception for dynamically-created install anchors.
- Supports install URLs using `tampermonkey://`, `violentmonkey://`, and `userscript://` when they contain a nested `url/script/src` target.
- Preserves the current page Referer and WebView Cookie when downloading the userscript.
- Validates the downloaded payload so HTML/error pages are not saved as scripts.
- Handles WebView download callbacks for `.user.js` payloads.
- Prevents duplicate installation requests from click/navigation/download callbacks.
- Reloads the current page after successful installation so `document-start` scripts take effect immediately.

## Compatibility
- Keeps the existing compileSdk 34 / AGP 8.5.2 / Media3 1.4.1 setup from V0.7.2.
- Does not intercept ordinary JavaScript resources unless they are clearly userscript installation payloads.
