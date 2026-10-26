"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useRef, useState } from "react";
import { useI18n } from "@/lib/i18n";
import { activeGroupId, NAV_GROUPS } from "@/lib/navigation";
import { NavIcon } from "./NavIcons";

interface SideState {
  path: string;
  expanded: boolean; // the labels are shown (the rail is opened)
  group: string | null; // the group whose sub-menu is listed below it
}

/**
 * The menu. A narrow rail of symbols: pressing one opens the rail with every name, and the group you pressed lists its pages below
 * it; pressing another group folds the first away. Choosing a page, Escape or a press outside folds the rail back to symbols. On a
 * phone the rail is out of the way until the Menu button of the top bar opens it. The state remembers the page it belongs to, so
 * moving to another page folds it without an effect.
 */
export function Sidebar({ mobileOpen, onMobileClose }: { mobileOpen: boolean; onMobileClose: () => void }) {
  const { t } = useI18n();
  const path = usePathname();
  const [stored, setStored] = useState<SideState>({ path, expanded: false, group: null });
  const state: SideState = stored.path === path ? stored : { path, expanded: false, group: null };
  const update = (patch: Partial<SideState>) => setStored({ ...state, ...patch, path });
  const buttons = useRef<Record<string, HTMLButtonElement | null>>({});
  const toggle = useRef<HTMLButtonElement>(null);
  const current = activeGroupId(path);
  const open = state.expanded || mobileOpen;

  function collapse(refocus = true) {
    const was = state.group;
    update({ expanded: false, group: null });
    onMobileClose();
    if (refocus) (was ? buttons.current[was] : toggle.current)?.focus();
  }

  function onGroup(id: string) {
    if (!open) update({ expanded: true, group: id });       // a symbol was pressed: open the rail and list that group
    else update({ expanded: true, group: state.group === id ? null : id });
  }

  function onKeyDown(e: React.KeyboardEvent) {
    if (e.key === "Escape" && open) collapse();
  }

  return (
    <>
      <aside className="sidebar" data-expanded={open} data-testid="sidebar" onKeyDown={onKeyDown}>
        <div className="sidebar-head">
          <button
            type="button" ref={toggle} className="sidebar-toggle" aria-expanded={open} aria-controls="main-menu"
            aria-label={open ? t("nav.collapse") : t("nav.expand")} title={open ? t("nav.collapse") : t("nav.expand")}
            data-testid="sidebar-toggle"
            onClick={() => (open ? collapse(false) : update({ expanded: true, group: current }))}
          >
            <NavIcon id={open ? "close" : "menu"} />
          </button>
          <span className="sidebar-title">{t("app.title")}</span>
        </div>
        <nav id="main-menu" className="mainnav" aria-label={t("nav.main")} data-testid="main-menu">
          <ul className="nav-groups">
            {NAV_GROUPS.map((g) => {
              const isOpen = open && state.group === g.id;
              return (
                <li key={g.id} className="nav-group" data-active={current === g.id}>
                  <button
                    type="button"
                    className="nav-group-btn"
                    ref={(el) => { buttons.current[g.id] = el; }}
                    aria-expanded={isOpen}
                    aria-controls={`nav-panel-${g.id}`}
                    title={t(g.labelKey)}
                    data-testid={`nav-group-${g.id}`}
                    onClick={() => onGroup(g.id)}
                  >
                    <NavIcon id={g.id} />
                    <span className="nav-label">{t(g.labelKey)}</span>
                    <span className="caret" aria-hidden="true">▾</span>
                  </button>
                  <ul id={`nav-panel-${g.id}`} className="nav-panel" hidden={!isOpen} data-testid={`nav-panel-${g.id}`}>
                    {g.items.map((item) => (
                      <li key={item.id}>
                        {item.status === "available" && item.href ? (
                          <Link href={item.href} aria-current={item.isCurrent?.(path) ? "page" : undefined} data-testid={`nav-item-${item.id}`} onClick={() => collapse(false)}>
                            {t(item.labelKey)}
                          </Link>
                        ) : (
                          <span className="nav-unavailable" aria-disabled="true" data-testid={`nav-item-${item.id}`}>
                            {t(item.labelKey)}
                            <small>{t("nav.unavailable")}</small>
                          </span>
                        )}
                      </li>
                    ))}
                  </ul>
                </li>
              );
            })}
          </ul>
        </nav>
      </aside>
      {open && <div className="sidebar-backdrop" data-testid="sidebar-backdrop" onClick={() => collapse(false)} />}
    </>
  );
}
