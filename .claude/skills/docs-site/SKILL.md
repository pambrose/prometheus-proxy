---
name: docs-site
description: Use when editing, building, previewing, or deploying the prometheus-proxy documentation site under website/prometheus-proxy — covers the Zensical config, code-snippet resolution, local preview, and CI deploy.
---

# Documentation site

Documentation is built with [Zensical](https://zensical.org) (static site generator) and lives in `website/prometheus-proxy/`.

- **Config**: `website/prometheus-proxy/zensical.toml`
- **Pages**: `website/prometheus-proxy/docs/` (Markdown with pymdownx extensions)
- **Code snippets**: `src/test/kotlin/website/*.txt` (imported via `--8<--` snippet markers)
- **Local preview**: `make site` (runs `cd website/prometheus-proxy && uv run --with mkdocs-material zensical serve`)
- **CI deploy**: `.github/workflows/docs.yml` (builds and deploys to GitHub Pages)

Snippets use dual `base_path` in zensical.toml: first resolves `.txt` files from `src/test/kotlin/website/`, second resolves project-root files like `examples/*.conf`.
