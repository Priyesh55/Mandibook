import type { Metadata } from "next";
import { AcceptInvitation } from "@/components/AcceptInvitation";

export const metadata: Metadata = { title: "Invitation" };

export default function AcceptInvitationPage() {
  return <AcceptInvitation />;
}
