import type { Metadata } from "next";
import { BillPrint } from "@/components/BillPrint";

export const metadata: Metadata = { title: "Print Aadat bill (draft)" };

export default async function BillPrintPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <BillPrint id={id} />;
}
