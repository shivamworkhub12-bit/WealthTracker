import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "WealthOS | Money overview",
  description: "A calm, private view of your monthly spending.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="en"><body>{children}</body></html>;
}
