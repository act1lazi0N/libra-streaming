import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "LIBRA Streaming",
  description: "A VOD platform built with Spring Boot and Next.js",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}

