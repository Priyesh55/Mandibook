"use client";

import { usePathname } from "next/navigation";
import { useState, type ReactNode } from "react";
import { useI18n } from "@/lib/i18n";
import { SessionProvider, useSession } from "@/lib/session";
import { AuthGate } from "./AuthGate";
import { Breadcrumbs } from "./Breadcrumbs";
import { DocumentTitle } from "./DocumentTitle";
import { Header } from "./Header";
import { Sidebar } from "./Sidebar";

/** The page frame: the sidebar (only inside a firm), the slim top bar and the page. */
function Frame({ children }: { children: ReactNode }) {
  const { state } = useSession();
  const path = usePathname();
  const signedIn = state.status === "ready";
  // the phone menu remembers the page it was opened on, so moving to another page folds it
  const [mobile, setMobile] = useState({ path, open: false });
  const mobileOpen = mobile.path === path && mobile.open;
  return (
    <>
      <DocumentTitle />
      {signedIn && <Sidebar mobileOpen={mobileOpen} onMobileClose={() => setMobile({ path, open: false })} />}
      <div className={signedIn ? "shell-main with-sidebar" : "shell-main"}>
        <Header mobileOpen={mobileOpen} onMobileToggle={() => setMobile({ path, open: !mobileOpen })} />
        <main className="container">
          <Breadcrumbs />
          <AuthGate>{children}</AuthGate>
        </main>
      </div>
    </>
  );
}

/** The authenticated application shell: session state and the frame around every page. */
export function AppShell({ children }: { children: ReactNode }) {
  const { lang } = useI18n();
  return (
    <SessionProvider lang={lang}>
      <Frame>{children}</Frame>
    </SessionProvider>
  );
}
