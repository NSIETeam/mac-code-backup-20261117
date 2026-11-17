import type { Metadata } from "next";
import "./globals.css";
import { appPath } from "./app-paths.mjs";

export const metadata: Metadata = {
  title: "思跃工作词典",
  description: "查岗位要求，理业务流程，让改进需求有据可循。",
  icons: {
    icon: appPath("/brand-icon.svg"),
    shortcut: appPath("/brand-icon.svg"),
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
