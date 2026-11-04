# Aster website v4

Static landing page and documentation, built with Python's standard library. No npm install or Python package installation is needed.

```sh
python3 build.py --output /tmp/aster-site-v4
```

`index.template.html` contains landing-page copy and layout. `assets/landing.css` contains desktop and mobile styles; `assets/landing.js` handles the small-screen navigation and checksum copy action. `content/` preserves the live site's existing guide, licenses and privacy content, with the new release documented separately.

The hero displays three overlapping phone objects using the two supplied Android screenshots. The light Codex screen sits in front, with two dark Pi screens behind. Device artwork is composed with the Mobile FIRST front mockup. Completed compositions are stored as `assets/aster-phone-1.svg` and `assets/aster-phone-2.svg`, including their raster image content. Only those completed compositions are required for subsequent builds.

Mockup source and commercial-use terms: https://www.webmobilefirst.com/en/mockups/apple-iphone-18-pro-2026/ . The empty mockup is excluded from distributed source and static releases; it is not offered as a standalone asset. `credits/` identifies the source and makes clear that Aster is an Android application.

The generated output is served by the existing nginx configuration at `/var/www/aster/current`, with versioned releases in `/var/www/aster/releases`. Shared downloads are under `/var/www/aster/downloads`, outside the versioned page directory. Account API, OAuth, administrator and MCP routes continue to use their existing services. Reverting the `current` symlink to the recorded previous release restores the earlier website.

Release 1.0.4 is labelled as a preview. Download metadata and checksum are pinned in `build.py`; update these together when publishing a later build. The old release metadata remains available inside `release.json` as `previous_public_release`.

The build validates local links and anchors and creates `site-manifest.json` plus `SITE-SHA256SUMS.txt`. Public deployment verification additionally checks every generated asset against its local SHA-256, page HTTP status, download range support, and the existing protected MCP endpoint.