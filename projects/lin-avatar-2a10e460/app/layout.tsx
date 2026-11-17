import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "林 · AI 对话室",
  description: "原创虚拟形象、轻量语音交互，和林一起讨论 AI。",
  icons: {
    icon: "/favicon.svg",
    shortcut: "/favicon.svg",
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN">
      <body className="antialiased">{children}</body>
    </html>
  );
}
