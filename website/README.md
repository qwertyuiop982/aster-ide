# Website entry points

The production compact homepage is maintained in [v5/](v5/README.md). Build it with `python3 website/v5/build.py --release-dir /path/to/release-v1.0.5 --output /path/to/site`; its version, APK hash and download URL come from verified delivery metadata. The production download proxy remains gh-proxy.org, targeting the v1.0.5 GitHub Release.

The following instructions describe the retained legacy multi-page generator and its historical 1.0.1 delivery, not the production deployment.

# Aster static website

The homepage, guide and component/license page are generated as static files. Interactive workflow previews are local illustrations. The website does not contact model providers or need a server. Published APK/source downloads use GitHub Release assets via `https://gh-proxy.org/https://github.com/qwertyuiop982/aster-ide/releases/download/v1.0.1/`.

Build from the verified Release directory containing delivery.json and the assets whose hashes it records:

```sh
python3 -B website/build_release_site.py --release-dir /path/to/release --site-url https://qwertyuiop982.github.io/aster-ide
```

The builder verifies every delivery asset, writes `build-v101/` with fingerprinted frontend assets and release metadata, and creates `aster-site-v101.tar.gz`. Set `--site-url https://your-domain.example` for a custom domain. `--repository OWNER/REPO` changes the Release repository; `--download-proxy ''` uses direct GitHub links.

Publish the generated static directory on the repository's `gh-pages` branch and configure GitHub Pages to serve that branch's root. Project-path prefixes are applied to navigation/assets so the default GitHub Pages project URL works. The generated `.nojekyll` file enables plain static hosting. Build output, site tarballs and downloaded APKs are ignored on the source branch.

APK/source/notice/validation/checksum/delivery files belong to the v1.0.1 GitHub Release. They are not copied into the website branch. The website's license remains the project license; third-party notices are retained.
