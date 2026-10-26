import type { Metadata } from "next";
import { Suspense } from "react";
import { BillView } from "@/components/BillView";

export const metadata: Metadata = { title: "Aadat bill" };

export default async function BillPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return (
    <Suspense>
      <BillView id={id} />
    </Suspense>
  );
}
