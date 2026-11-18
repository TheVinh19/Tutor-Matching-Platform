import type { Metadata } from "next";
import "./globals.css";
export const metadata: Metadata = { title: "Nền tảng gia sư", description: "Khung dự án nền tảng gia sư" };
export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="vi"><body>{children}</body></html>;
}
