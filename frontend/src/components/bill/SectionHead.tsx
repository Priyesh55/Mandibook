import type { ReactNode } from "react";

/** The heading of one section: number, title and an optional right-hand slot. Same markup on every section and page. */
export function SectionHead({ id, number, title, children }: { id: string; number: number; title: string; children?: ReactNode }) {
  return (
    <header className="sec-head">
      <span className="sec-num" aria-hidden>{number}</span>
      <h2 id={id}>{title}</h2>
      {children && <div className="spacer">{children}</div>}
    </header>
  );
}
