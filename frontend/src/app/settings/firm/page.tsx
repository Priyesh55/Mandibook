import type { Metadata } from "next";
import { FirmSettings } from "@/components/FirmSettings";

export const metadata: Metadata = { title: "Firm settings" };

export default function FirmSettingsPage() {
  return <FirmSettings />;
}
