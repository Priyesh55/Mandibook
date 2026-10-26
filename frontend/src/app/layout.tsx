import type { Metadata } from "next";
import { Noto_Sans, Noto_Sans_Devanagari } from "next/font/google";
import "./globals.css";
import { LanguageProvider } from "@/lib/i18n";
import { AppShell } from "@/components/AppShell";

const latin = Noto_Sans({ variable: "--font-latin", subsets: ["latin"] });
// Devanagari glyphs are required for Hindi screens and the printable bill.
const devanagari = Noto_Sans_Devanagari({ variable: "--font-deva", subsets: ["devanagari", "latin"] });

export const metadata: Metadata = {
  title: { default: "Mandi Book", template: "%s | Mandi Book" },
  description: "Mandi Book: Aadat billing for the family business (demo build, synthetic data only)",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en" className={`${latin.variable} ${devanagari.variable}`}>
      <body>
        <LanguageProvider>
          <AppShell>{children}</AppShell>
        </LanguageProvider>
      </body>
    </html>
  );
}
