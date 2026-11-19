import type { Metadata } from "next";
import "./globals.css";
import { Be_Vietnam_Pro } from "next/font/google";
import { cn } from "@/lib/utils";
import { SiteHeader } from "@/components/layout/site-header";

// Geist has no Vietnamese subset; Be Vietnam Pro renders diacritics correctly.
const font = Be_Vietnam_Pro({ subsets: ["latin", "vietnamese"], weight: ["400", "500", "600", "700"], variable: "--font-sans" });

export const metadata: Metadata = { title: "Gia Sư Cam", description: "Nền tảng kết nối gia sư" };
export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="vi" className={cn("font-sans", font.variable)}><body className="min-h-screen bg-background text-foreground antialiased"><SiteHeader /><main className="mx-auto max-w-6xl px-4 py-8">{children}</main></body></html>;
}
