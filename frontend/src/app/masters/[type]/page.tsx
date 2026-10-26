import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { MasterManager } from "@/components/MasterManager";
import type { MasterType } from "@/lib/types";

const TYPES = ["farmers", "buyers", "villages", "items", "expense-heads"];
const TITLES: Record<string, string> = {
  farmers: "Farmers",
  buyers: "Buyers",
  villages: "Villages",
  items: "Items",
  "expense-heads": "Expense heads",
};

export async function generateMetadata({ params }: { params: Promise<{ type: string }> }): Promise<Metadata> {
  const { type } = await params;
  return { title: TITLES[type] ?? "Masters" };
}

export default async function MastersPage({ params }: { params: Promise<{ type: string }> }) {
  const { type } = await params;
  if (!TYPES.includes(type)) notFound();
  // key: switching between master types starts from a clean search and the first page
  return <MasterManager key={type} type={type as MasterType} />;
}
