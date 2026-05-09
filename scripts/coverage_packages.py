#!/usr/bin/env python3
# Print per-package Kover coverage from build/reports/kover/report.xml.
# Used by `make coverage-packages`.
#
# B405/B314: We deliberately use the stdlib xml.etree.ElementTree here. The
# input is build/reports/kover/report.xml — produced by Kover on this build,
# never user-supplied — so XXE / entity-bomb attacks aren't a real threat
# model. defusedxml would just add a dependency for no security gain.

import xml.etree.ElementTree as ET  # nosec B405
import sys


def main() -> int:
    report_path = "build/reports/kover/report.xml"
    try:
        root = ET.parse(report_path).getroot()  # nosec B314
    except FileNotFoundError:
        print(f"Coverage report not found at {report_path}; run `make coverage-xml` first.", file=sys.stderr)
        return 1

    pkgs: list[tuple[str, int, int]] = []
    for pkg in root.findall("package"):
        for counter in pkg.findall("counter"):
            if counter.get("type") == "INSTRUCTION":
                pkgs.append((pkg.get("name", ""), int(counter.get("covered", "0")), int(counter.get("missed", "0"))))

    pkgs.sort(key=lambda x: -x[2])

    print(f"{'package':<55} {'cov%':>6} {'covered':>9} {'missed':>9} {'total':>9}")
    for name, covered, missed in pkgs:
        total = covered + missed
        pct = (covered / total * 100) if total else 0.0
        print(f"{name:<55} {pct:6.1f} {covered:9d} {missed:9d} {total:9d}")

    total_covered = sum(p[1] for p in pkgs)
    total_missed = sum(p[2] for p in pkgs)
    grand_total = total_covered + total_missed
    if grand_total:
        print(f"\nOVERALL: {total_covered / grand_total * 100:.2f}% "
              f"({total_covered}/{grand_total} instructions, {total_missed} missed)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
