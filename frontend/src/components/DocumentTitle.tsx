"use client";

import { usePathname } from "next/navigation";
import { useEffect } from "react";
import { useI18n } from "@/lib/i18n";
import { titleKeyFor } from "@/lib/navigation";

// The language is a client-side setting, so the server can only send the English title (see layout.tsx
// metadata). Once the page is in the browser this keeps the tab title and <html lang> in the chosen language.
export function DocumentTitle() {
  const { t, lang } = useI18n();
  const key = titleKeyFor(usePathname());
  const title = key ? `${t(key)} | ${t("app.title")}` : null;

  useEffect(() => {
    document.documentElement.lang = lang;
    if (!title) return;
    const apply = () => {
      if (document.title !== title) document.title = title;
    };
    apply();
    // Next.js rewrites the title after client-side navigation; re-apply ours if it does.
    const observer = new MutationObserver(apply);
    observer.observe(document.head, { childList: true, subtree: true, characterData: true });
    return () => observer.disconnect();
  }, [title, lang]);

  return null;
}
