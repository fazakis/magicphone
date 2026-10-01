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

The call to action links to the source/build instructions. No APK is published by the website workflow, and the site does not advertise an unavailable production release.

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
