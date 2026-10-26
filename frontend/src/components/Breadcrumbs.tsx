"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useI18n } from "@/lib/i18n";
import { breadcrumbsFor } from "@/lib/navigation";

export function Breadcrumbs() {
  const { t } = useI18n();
  const crumbs = breadcrumbsFor(usePathname());
  if (crumbs.length === 0) return null;
  return (
    <nav className="breadcrumbs no-print" aria-label={t("nav.breadcrumb")} data-testid="breadcrumbs">
      <ol>
        {crumbs.map((c, i) => {
          const last = i === crumbs.length - 1;
          return (
            <li key={`${c.labelKey}-${i}`} aria-current={last ? "page" : undefined}>
              {c.href && !last ? <Link href={c.href}>{t(c.labelKey)}</Link> : t(c.labelKey)}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
