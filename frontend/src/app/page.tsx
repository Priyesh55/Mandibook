import type { Metadata } from "next";
import { BillSearch } from "@/components/BillSearch";

// The layout's title template only applies to child segments, so the home page spells its full title.
export const metadata: Metadata = { title: { absolute: "Aadat bills | Mandi Book" } };

export default function Home() {
  return <BillSearch />;
}
