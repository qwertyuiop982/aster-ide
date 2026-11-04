# Aster compact website

A short, dependency-free product page. Three accessible tabs present Agent, editor and terminal capabilities without adding scrolling sections. Assets are served locally and work under the existing strict CSP. Reduced-motion preferences are respected.

```sh
python3 website/v5/build.py --output /tmp/aster-site-v5
python3 -m http.server 8137 --bind 0.0.0.0 --directory /tmp/aster-site-v5
```

APK buttons point to the v1.0.4 GitHub Release through gh-proxy.org. The website contains no APK or source archive, account UI, credit display, API keys or third-party assets. Backend routes and nginx security policy are left unchanged. The small privacy document is separate from the landing page.

`deploy_remote.py` verifies the static bundle, backs up the previous target, atomically switches `/var/www/aster/current` and automatically rolls back if homepage or backend checks fail. Credentials are supplied externally; never place them in this directory.
