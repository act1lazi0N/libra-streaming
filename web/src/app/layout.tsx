import type { Metadata } from "next";
import { AppHeader } from "@/components/AppHeader";
import { Providers } from "@/components/Providers";
import "./globals.css";

export const metadata: Metadata = {
  title: "LIBRA Streaming",
  description: "A VOD platform built with Spring Boot and Next.js",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="vi">
      <body>
        <Providers>
          <AppHeader />
          {children}
        </Providers>
      </body>
    </html>
  );
}
