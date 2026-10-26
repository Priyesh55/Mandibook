"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useI18n } from "@/lib/i18n";
import { useSession } from "@/lib/session";
import { NavIcon } from "./NavIcons";
import { SessionBar } from "./SessionBar";

/**
 * The slim top bar: the product name, the New bill shortcut, the language and who is signed in. The menu itself is the sidebar.
 * On a phone the Menu button here opens the sidebar.
 */
export function Header({ mobileOpen, onMobileToggle }: { mobileOpen: boolean; onMobileToggle: () => void }) {
  const { t, lang, setLang } = useI18n();
  const { state } = useSession();
  const signedIn = state.status === "ready"; // the menu and the new-bill shortcut exist only inside a firm
  const path = usePathname();

  return (
    <>
      <div className="demo-banner" data-testid="demo-banner">
        {t("app.demoBanner")}
      </div>
      <header className="topbar">
        <div className="topbar-inner">
          {signedIn && (
            <button
              type="button" className="menu-toggle" aria-expanded={mobileOpen} aria-controls="main-menu"
              aria-label={t("nav.menu")} data-testid="menu-toggle" onClick={onMobileToggle}
            >
              <NavIcon id="menu" />
              <span className="menu-toggle-text">{t("nav.menu")}</span>
            </button>
          )}
          <Link href="/" className="brand" data-testid="brand">
            {t("app.title")}
          </Link>
          <div className="topbar-right">
            {signedIn && (
              <Link href="/bills/new" className="button cta" aria-current={path === "/bills/new" ? "page" : undefined} data-testid="nav-new-bill">
                + {t("nav.newBill")}
              </Link>
            )}
            <label className="lang">
              <span className="lang-label">{t("lang.label")}</span>
              <select aria-label={t("lang.label")} data-testid="lang-select" value={lang} onChange={(e) => setLang(e.target.value === "hi" ? "hi" : "en")}>
                <option value="en">English</option>
                <option value="hi">हिन्दी</option>
              </select>
            </label>
            <SessionBar />
          </div>
        </div>
      </header>
    </>
  );
}
