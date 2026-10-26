"use client";

import { createContext, useCallback, useContext, useEffect, useSyncExternalStore, type ReactNode } from "react";
import { dict, type MessageKey } from "./dictionary";
import type { Lang } from "./types";

export type { MessageKey };



// --- tiny external store for the language, persisted in localStorage ---
const STORAGE_KEY = "mandi.lang";
const listeners = new Set<() => void>();
let sessionLang: Lang | null = null; // keeps switching working when localStorage is unavailable

function readLang(): Lang {
  if (sessionLang) return sessionLang;
  try {
    const v = window.localStorage.getItem(STORAGE_KEY);
    return v === "hi" ? "hi" : "en";
  } catch {
    return "en";
  }
}

function subscribe(cb: () => void) {
  listeners.add(cb);
  return () => listeners.delete(cb);
}

interface I18n {
  lang: Lang;
  setLang: (l: Lang) => void;
  t: (key: MessageKey | string) => string;
}

const Ctx = createContext<I18n | null>(null);

export function LanguageProvider({ children }: { children: ReactNode }) {
  const lang = useSyncExternalStore(subscribe, readLang, () => "en" as Lang);

  const setLang = useCallback((l: Lang) => {
    sessionLang = l;
    try {
      window.localStorage.setItem(STORAGE_KEY, l);
    } catch {
      /* storage unavailable: language still switches for this session via listeners */
    }
    listeners.forEach((cb) => cb());
  }, []);

  useEffect(() => {
    document.documentElement.lang = lang;
  }, [lang]);

  const t = useCallback(
    (key: string) => {
      const table = dict[lang] as Record<string, string>;
      return table[key] ?? (dict.en as Record<string, string>)[key] ?? key;
    },
    [lang],
  );

  return <Ctx.Provider value={{ lang, setLang, t }}>{children}</Ctx.Provider>;
}

export function useI18n(): I18n {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("useI18n must be used inside LanguageProvider");
  return ctx;
}
