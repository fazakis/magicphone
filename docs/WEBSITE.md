# MagicPhone website

The landing page is in `site/` in the same repository as the Android app. It is plain HTML, CSS and a small JavaScript file: no package installation, build step, analytics, remote fonts or backend is required. Its original icon and social artwork share the app's MIT OR Apache-2.0 license choice.

## Local preview

```sh
python3 -m http.server 8765 --bind 127.0.0.1 --directory site
```

Open `http://127.0.0.1:8765`. The three screenshot selectors show the actual home screen, model settings and a completed practice task captured on the dedicated emulator. Tap the phone to view the selected image at full size. The home capture is from the 0.2.0 UI refresh (unchanged in 0.2.1); the settings and task captures are from 0.2.1. Images are copied without visual alteration and contain no account identifiers. They do not connect to or operate any actual device. The FAQ uses native HTML disclosure controls and works without JavaScript. Main content and links also remain available with scripts disabled. The page supports mobile layouts, keyboard focus and reduced motion.

## Deployment

`.github/workflows/pages.yml` uploads only `site/` to GitHub Pages on changes to that folder or its own workflow on `main`, and supports manual dispatch. Actions are pinned to upstream commits. Android CI ignores website-only changes. Application source, internal instructions, test artifacts, APKs, signing keys and build directories are never included in the Pages artifact.

The website is published at [magicphone.org](https://magicphone.org/). The canonical URL, sitemap and Open Graph metadata use that address.

The call to action links to the 0.2.2 early-access GitHub Release, with source/build instructions still available. The release identifies the installable APK as debug signed. APKs are separate GitHub Release assets; the website workflow only publishes static site files.

## Content updates

- Edit `index.html` for copy, links, FAQs and metadata.
- Keep `disclaimer.html` consistent with the README notice and app onboarding/access copy.
- Edit `styles.css` for presentation and breakpoints.
- Edit `script.js` for screenshot selection and accessible captions. Use authentic app captures in `site/assets/app-*.png`; do not imply that a selected processing speed was confirmed by the server.
- Keep provider/permission/privacy claims consistent with the Android implementation and `docs/PRIVACY.md`.
- Keep the source and MIT/Apache license links valid. Third-party dependencies are not relicensed.
- Update social metadata and `sitemap.xml` if the public domain changes.

This website change does not change the Android app version or runtime behavior.

## Executed verification (2026-10-02)

- Published the repository at https://github.com/fazakis/magicphone; the default branch is `main`. GitHub recognizes the primary MIT license; Apache-2.0 remains an explicit alternative in LICENSE-APACHE and original-source SPDX headers.
- [First Pages deployment](https://github.com/fazakis/magicphone/actions/runs/36931116016) passed. Only the static site was uploaded.
- Visually inspected desktop, tablet (768 px) and narrow phone (320/390 px) layouts. Checked horizontal overflow, all three example interactions, FAQ expansion and local asset/anchor integrity. No browser console errors or missing images were found.
- The published HTML and CSS matched the local files by SHA-256.
- Verified that the public website loads successfully over HTTPS.
- The Android app remains at 0.1.5. Source changes in this task are licensing headers, documentation and publishing configuration; Android runtime behavior is unchanged. The first repository push also starts the existing Android verification workflow.
- `INSTRUCTIONS.md`, local build settings, APKs, keys and `artifacts/` are excluded from Git. Original private build-host addresses were removed from the public developer helper and verification records.

The first public CI build passed compilation, unit tests, lint and release checks. Its Android 11 emulator exposed four assumptions in the previously Android-15-specific UI harness: a floating shortcut rather than Android 11's navigation button, a hardcoded Google speech package, and automatic shade dismissal (an API 31+ action). The harness now configures the version-appropriate shortcut, resolves an installed speech provider or explicitly skips that external-provider check when absent, and closes the shade manually on Android 11 while still asserting Pause/Resume/Stop. CI now runs both API 30 and API 35, with test-only notification permission setup and on-screen keyboard enabled. No production application behavior was changed to address these test-environment differences.

The follow-up [hosted matrix run](https://github.com/fazakis/magicphone/actions/runs/36932298773) still failed: two shortcut tests on API 30 and five fixture/keyboard/voice tests on API 35. Build/core/lint/release checks passed. The remaining device failures are unresolved and are disclosed in the early-access APK release; the previously recorded local Android 15 checks are separate evidence.


## Screenshot refresh for 0.2.1 (2026-10-02)

The user requested previews closer to the real app. The hero now displays authentic, unaltered app screenshots, replacing the earlier HTML conversation illustration. Three selectors show Home, model/thinking settings and a completed practice task; the image links to its full-size original. The settings and result captions distinguish requested processing speed from actual server-reported speed. The phone frame preserves the screenshots' full aspect ratio, and decorative labels no longer cover app controls.

Browser verification covered 1280 px desktop, 768 px tablet and 390/320 px phone widths, screenshot switching (including keyboard activation), selected state, full-size image destinations, FAQ expansion, image loading, local asset/anchor integrity and JavaScript syntax. No horizontal page overflow or browser warning/error was observed. The final 320 px check confirmed the whole phone fits and the caption does not overlap it. Preview captures are in ignored `artifacts/github-release-0.2.1/`. The release download points to v0.2.1.

## 0.2.2 disclaimer and release links (2026-10-02)

The install/download links now target v0.2.2. The new `disclaimer.html` carries the approved open-source automation notice, linked from the footer and install section and included in the sitemap. Desktop and 320 px phone checks confirm readable layout, no horizontal overflow, valid Home/license links and working homepage-to-disclaimer navigation. The initial narrow-header overlap was fixed. Existing authentic app screenshots remain unchanged. No DNS operational details or personal marketing bylines were reintroduced.

## 0.2.3 download update (2026-10-02)

Homepage version and download links now target v0.2.3. Existing screenshots, disclaimer and domain configuration are unchanged.

## 0.2.4 download update (2026-10-02)

Homepage version and download links target v0.2.4. Local links and the release URL are checked as part of publication.

## 0.2.5 download update (2026-10-03)

Homepage version and download links target v0.2.5 with popup dictation, fresh screen context and progress controls. Publication verification checks the deployed page and release URL.

## 0.2.6 download update (2026-10-03)

Homepage version and download links target v0.2.6 with popup action continuity and configurable sensitive-content checks.

Version 0.2.7 updates the landing-page release link to the large-screenshot/action-context hotfix.

Version 0.2.9 updates the landing-page download link to the general visible-window and focus-handling release.

Version 0.2.10 updates the landing-page download link to the retained-photo and conversation-context release.
