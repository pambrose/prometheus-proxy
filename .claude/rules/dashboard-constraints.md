---
paths:
  - "src/main/kotlin/io/prometheus/proxy/dashboard/**"
  - "src/test/kotlin/io/prometheus/proxy/dashboard/**"
---

# Dashboard Design Constraints

`DESIGN.md` is the visual contract (color tokens, typography roles, components) and `PRODUCT.md` the product one; both are tracked, while the tooling that generated them is gitignored. Three constraints in `ProxyDashboardHtml.kt` are load-bearing and easy to undo by accident:

- **The live region must stay outside every `hx-swap-oob` region.** A region the push loop rewrites re-announces itself on every frame. `ProxyDashboardHtmlTest` pins this.
- **Check a color against `--surface-2`, not `--surface`.** Every token also lands on the tighter ground under tables and section headers, which is where all 29 of the contrast failures fixed in 4.0.1 lived. The measured AA floor is 4.59:1 — `DESIGN.md` records the per-token values.
- **Both layouts must render agent identity through `agentLabel()`.** The agent and path views are read against each other, so a divergent label (or a raw `agentId`) defeats the correlation they exist for.
