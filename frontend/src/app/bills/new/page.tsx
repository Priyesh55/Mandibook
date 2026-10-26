import type { Metadata } from "next";
import { Suspense } from "react";
import { BillForm } from "@/components/BillForm";

export const metadata: Metadata = { title: "New Aadat bill" };

export default function NewBillPage() {
  return (
    <Suspense>
      <BillForm />
    </Suspense>
  );
}
