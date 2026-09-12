---
paths:
  - "src/main/kotlin/io/prometheus/proxy/dashboard/**"
  - "src/test/kotlin/io/prometheus/proxy/dashboard/**"
---

# Dashboard Design Constraints

`docs/DESIGN.md` is the visual contract (color tokens, typography roles, components) and `docs/PRODUCT.md` the product one; both are tracked, while the tooling that generated them is gitignored. Three constraints in `ProxyDashboardHtml.kt` are load-bearing and easy to undo by accident:

- **The live region must stay outside every `hx-swap-oob` region.** A region the push loop rewrites re-announces itself on every frame. `ProxyDashboardHtmlTest` pins this.
- **Check a color against `--surface-2`, not `--surface`.** Every token also lands on the tighter ground under tables and section headers, which is where all 29 of the contrast failures fixed in 4.0.1 lived. The measured AA floor is 4.59:1 — `docs/DESIGN.md` records the per-token values.
- **Both layouts must render agent identity through `agentLabel()`.** The agent and path views are read against each other, so a divergent label (or a raw `agentId`) defeats the correlation they exist for.
- **The route base arrives already normalized; do not normalize it again per use site.** `ProxyDashboardService` derives `routeBase` once (trailing slash stripped, so it is empty at a root mount) and hands the same value to its own `routing { }` block and to `renderPage`/`pushFragment`. Sub-routes are then plain interpolation. Re-adding a per-site join helper splits the rule across two layers, which is how the router and the links came to disagree before: the links were fixed while the routes still built `//paths`, and only Ktor discarding empty segments hid it. The nav's own link is the base itself, so it must render as `/` rather than collapse to an empty `href`. `ProxyDashboardHtmlTest` pins both.
