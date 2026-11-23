---
name: docs-site
description: Use when editing, building, previewing, or deploying the prometheus-proxy documentation site under website/prometheus-proxy — covers the Zensical config, code-snippet resolution, local preview, and CI deploy.
---

# Documentation site

Documentation is built with [Zensical](https://zensical.org) (static site generator) and lives in `website/prometheus-proxy/`.

- **Config**: `website/prometheus-proxy/zensical.toml`
- **Pages**: `website/prometheus-proxy/docs/` (Markdown with pymdownx extensions)
- **Code snippets**: `src/test/kotlin/website/*.txt` (imported via `--8<--` snippet markers)
- **Screenshots**: the Basics Demo page's images (`docs/images/demo/*.webp`) come from `make screenshots` in `demos/basics` (the demo's own Makefile), which runs `demos/basics/bin/screenshots.sh` against a demo built from the checkout; the Network Demo page's (`docs/images/network-demo/*.webp`) come the same way from `make screenshots` in `demos/network`, the nginx Demo page's (`docs/images/nginx-demo/*.webp`) from `make screenshots` in `demos/nginx`, and the Kubernetes Demo page's (`docs/images/kubernetes-demo/*.webp`) from `make screenshots` in `demos/kubernetes`, which also needs kind and Helm. Retake them after a visible dashboard change
- **Local preview**: `make site` (runs `cd website/prometheus-proxy && uv run zensical serve`; mkdocs-material is pinned in `website/pyproject.toml`)
- **Build check**: `make build-site` (runs `uv run --locked zensical build --clean`), as CI's `docs-site` job does on every pull request; a missing snippet source fails it, since `zensical.toml` sets `check_paths = true`
- **CI deploy**: `.github/workflows/docs.yml` (builds the site and the KDocs and deploys to GitHub Pages). Only a release deploys: a push to `master` builds both as a check without deploying, and `make release` deploys with `make gh-docs` (a `workflow_dispatch`) once it has published, so the live site describes the last release until the next one, docs changes merged in between included. `make gh-docs` by hand deploys `master` as it is, unreleased pages included. CI installs a pinned uv (`version:` on both `setup-uv` steps); update it with the uv `website/uv.lock` is locked with
- **Dependencies**: `website/pyproject.toml` and `website/uv.lock`, updated weekly by Dependabot's `docs-site` group; `make upgrade-site` upgrades by hand with the same 7-day cooldown (`SITE_COOLDOWN`), and `make check-site` lists what it would change

Snippets use dual `base_path` in zensical.toml: first resolves `.txt` files from `src/test/kotlin/website/`, second resolves project-root files like `examples/*.conf`.
