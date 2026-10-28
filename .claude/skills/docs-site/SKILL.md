---
name: docs-site
description: Use when editing, building, previewing, or deploying the prometheus-proxy documentation site under website/prometheus-proxy — covers the Zensical config, code-snippet resolution, local preview, and CI deploy.
---

# Documentation site

Documentation is built with [Zensical](https://zensical.org) (static site generator) and lives in `website/prometheus-proxy/`.

- **Config**: `website/prometheus-proxy/zensical.toml`
- **Pages**: `website/prometheus-proxy/docs/` (Markdown with pymdownx extensions)
- **Code snippets**: `src/test/kotlin/website/*.txt` (imported via `--8<--` snippet markers)
- **Local preview**: `make site` (runs `cd website/prometheus-proxy && uv run zensical serve`; mkdocs-material is pinned in `website/pyproject.toml`)
- **Build check**: `make build-site` (runs `uv run --locked zensical build --clean`), as CI's `docs-site` job does on every pull request; a missing snippet source fails it, since `zensical.toml` sets `check_paths = true`
- **CI deploy**: `.github/workflows/docs.yml` (builds the site and the KDocs and deploys to GitHub Pages). A push to `master` deploys only once the `gradle.properties` version is on Maven Central and Docker Hub, so a merged version bump waits for `make release`, which deploys with `make gh-docs` (a `workflow_dispatch`, which always deploys)
- **Dependencies**: `website/pyproject.toml` and `website/uv.lock`, updated weekly by Dependabot's `docs-site` group; `make upgrade-site` upgrades by hand with the same 7-day cooldown (`SITE_COOLDOWN`), and `make check-site` lists what it would change

Snippets use dual `base_path` in zensical.toml: first resolves `.txt` files from `src/test/kotlin/website/`, second resolves project-root files like `examples/*.conf`.
