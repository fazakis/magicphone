# MagicPhone website

The landing page is in `site/` in the same repository as the Android app. It is plain HTML, CSS and a small JavaScript file: no package installation, build step, analytics, remote fonts or backend is required. Its original icon and social artwork share the app's MIT OR Apache-2.0 license choice.

## Local preview

```sh
python3 -m http.server 8765 --bind 127.0.0.1 --directory site
```

Open `http://127.0.0.1:8765`. The three task selectors change the illustrated phone conversation; they do not connect to or operate any actual device. The FAQ uses native HTML disclosure controls and works without JavaScript. Main content and links also remain available with scripts disabled. The page supports mobile layouts, keyboard focus and reduced motion.

## Deployment

`.github/workflows/pages.yml` uploads only `site/` to GitHub Pages on changes to that folder or its own workflow on `main`, and supports manual dispatch. Actions are pinned to upstream commits. Android CI ignores website-only changes. Application source, internal instructions, test artifacts, APKs, signing keys and build directories are never included in the Pages artifact.

The source repository is `fazakis/magicphone`. Configure Pages to use **GitHub Actions**, then set its custom domain to **magicphone.org**. The custom domain is configured in the Pages API/settings; Actions deployments do not use a CNAME file as configuration. The canonical URL, sitemap and Open Graph metadata already use `https://magicphone.org/`.

The call to action links to the 0.1.5 early-access GitHub Release, with source/build instructions still available. The release identifies the installable APK as debug signed. APKs are separate GitHub Release assets; the website workflow only publishes static site files.

## Cloudflare DNS

The domain uses Cloudflare nameservers. For GitHub Pages, add these records with **DNS only** (grey cloud) while GitHub verifies the domain and provisions its certificate:

| Type | Name | Content |
|---|---|---|
| A | @ | 185.199.108.153 |
| A | @ | 185.199.109.153 |
| A | @ | 185.199.110.153 |
| A | @ | 185.199.111.153 |
| CNAME | www | fazakis.github.io |

Use Auto TTL. Preserve existing mail/TXT records. Replace conflicting website records for `@` or `www` if any exist; do not create wildcard records. Optional IPv6 AAAA records for `@` are `2606:50c0:8000::153`, `2606:50c0:8001::153`, `2606:50c0:8002::153`, and `2606:50c0:8003::153`.

Set the custom domain on GitHub before adding DNS records. After the DNS check succeeds and GitHub provisions the certificate, enable **Enforce HTTPS** in repository Settings → Pages. GitHub advises allowing up to 24 hours for DNS/certificate availability. For additional ownership protection, GitHub account Settings → Pages provides a domain-verification TXT record; use the exact value shown for the account.

Source: [GitHub's custom domain documentation](https://docs.github.com/en/pages/configuring-a-custom-domain-for-your-github-pages-site/managing-a-custom-domain-for-your-github-pages-site) and [Pages workflow documentation](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages).

## Content updates

- Edit `index.html` for copy, links, FAQs and metadata.
- Edit `styles.css` for presentation and breakpoints.
- Edit `script.js` for the three illustrative examples. Keep their status clearly illustrative.
- Keep provider/permission/privacy claims consistent with the Android implementation and `docs/PRIVACY.md`.
- Keep the source and MIT/Apache license links valid. Third-party dependencies are not relicensed.
- Update social metadata and `sitemap.xml` if the public domain changes.

This website change does not change the Android app version or runtime behavior.

## Executed verification (2026-10-02)

- Published the repository at https://github.com/fazakis/magicphone; the default branch is `main`. GitHub recognizes the primary MIT license; Apache-2.0 remains an explicit alternative in LICENSE-APACHE and original-source SPDX headers.
- [First Pages deployment](https://github.com/fazakis/magicphone/actions/runs/36931116016) passed. Only the static site was uploaded.
- Visually inspected desktop, tablet (768 px) and narrow phone (320/390 px) layouts. Checked horizontal overflow, all three example interactions, FAQ expansion and local asset/anchor integrity. No browser console errors or missing images were found.
- Fetched the published HTML and CSS from GitHub Pages using the custom hostname with a direct DNS override; their SHA-256 values matched the local files.
- Added all five records above through the user-authorized Cloudflare session, with DNS-only status and Auto TTL. Cloudflare retained the records after reload, and both public resolvers 1.1.1.1 and 8.8.8.8 resolved the four apex addresses and www CNAME.
- GitHub approved a certificate covering `magicphone.org` and `www.magicphone.org`; the user enabled Enforce HTTPS. Verified a certificate-validated HTTPS 200 for the apex, HTTP → HTTPS 301, and www → apex 301. This Mac still had a cached negative DNS answer, so these origin checks used the published IP with the correct hostname; the user independently confirmed the live site works.
- The Android app remains at 0.1.5. Source changes in this task are licensing headers, documentation and publishing configuration; Android runtime behavior is unchanged. The first repository push also starts the existing Android verification workflow.
- `INSTRUCTIONS.md`, local build settings, APKs, keys and `artifacts/` are excluded from Git. Original private build-host addresses were removed from the public developer helper and verification records.

The first public CI build passed compilation, unit tests, lint and release checks. Its Android 11 emulator exposed four assumptions in the previously Android-15-specific UI harness: a floating shortcut rather than Android 11's navigation button, a hardcoded Google speech package, and automatic shade dismissal (an API 31+ action). The harness now configures the version-appropriate shortcut, resolves an installed speech provider or explicitly skips that external-provider check when absent, and closes the shade manually on Android 11 while still asserting Pause/Resume/Stop. CI now runs both API 30 and API 35, with test-only notification permission setup and on-screen keyboard enabled. No production application behavior was changed to address these test-environment differences.

The follow-up [hosted matrix run](https://github.com/fazakis/magicphone/actions/runs/36932298773) still failed: two shortcut tests on API 30 and five fixture/keyboard/voice tests on API 35. Build/core/lint/release checks passed. The remaining device failures are unresolved and are disclosed in the early-access APK release; the previously recorded local Android 15 checks are separate evidence.
