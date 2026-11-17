"""Create one user-domain installer with a small app and its private bundled runtime."""
import hashlib
import json
from pathlib import Path
import platform
import plistlib
import shutil
import subprocess
import tempfile
import uuid
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
IDENTIFIER = "ai.siyue.ipworkbench"


def build(runtime: Path, output: Path) -> Path:
    manifest = json.loads((runtime / "operations/vendor/desktop-build.json").read_text())
    architecture = {"aarch64": "arm64", "x86_64": "x86_64"}[manifest["arch"]]
    if platform.system() != "Darwin" or platform.machine() != architecture:
        raise ValueError("Build the Mac installer on the matching native architecture")
    # A new launcher and its matching runtime can coexist with a previous running process.
    release = uuid.uuid4().hex
    version = manifest["version"].split("-")[0]
    output.mkdir(parents=True, exist_ok=True)
    destination = output / f"IPWorkbench-macOS-{architecture}.pkg"
    with tempfile.TemporaryDirectory(prefix="ip-pkg-") as directory:
        temp = Path(directory)
        payload = temp / "payload"
        library = payload / "Library/Application Support/Siyue/IPWorkbench/Runtimes" / release
        library.parent.mkdir(parents=True)
        shutil.copytree(runtime, library, symlinks=True)
        app = payload / "Applications/IP工作台.app/Contents"
        binaries = app / "MacOS"
        binaries.mkdir(parents=True)
        (app / "Info.plist").write_bytes(plistlib.dumps({
            "CFBundleIdentifier": IDENTIFIER, "CFBundleName": "IP 工作台",
            "CFBundleExecutable": "IPWorkbench", "CFBundlePackageType": "APPL",
            "CFBundleVersion": version, "CFBundleShortVersionString": version,
            "LSMinimumSystemVersion": "12.0", "SiyueRuntimeID": release,
        }))
        subprocess.run(["clang", "-Os", "-fobjc-arc", "-mmacosx-version-min=12.0", "-framework",
                        "AppKit", HERE / "macos_launcher.m", "-o", binaries / "IPWorkbench"], check=True)
        subprocess.run(["codesign", "--force", "--sign", "-", "--timestamp=none", app.parent], check=True)
        # Install at these exact paths; do not relocate an existing checkout's Chromium or workbench.
        (temp / "components.plist").write_bytes(plistlib.dumps([]))
        component = temp / "workbench.pkg"
        subprocess.run(["pkgbuild", "--root", payload, "--install-location", "/",
                        "--ownership", "recommended", "--component-plist", temp / "components.plist",
                        "--identifier", IDENTIFIER, "--version", version, component], check=True)
        distribution = ET.Element("installer-gui-script", {"minSpecVersion": "2"})
        ET.SubElement(distribution, "title").text = "思跃 AI 实验室 · IP 工作台"
        ET.SubElement(distribution, "domains", enable_anywhere="false",
                      enable_currentUserHome="true", enable_localSystem="false")
        ET.SubElement(distribution, "options", customize="never", hostArchitectures=architecture)
        ET.SubElement(ET.SubElement(distribution, "choices-outline"), "line", choice="desktop")
        choice = ET.SubElement(distribution, "choice", id="desktop", visible="false")
        ET.SubElement(choice, "pkg-ref", id=IDENTIFIER)
        ET.SubElement(distribution, "pkg-ref", id=IDENTIFIER, version=version).text = component.name
        xml = temp / "Distribution"
        ET.ElementTree(distribution).write(xml, encoding="utf-8", xml_declaration=True)
        product = temp / destination.name
        subprocess.run(["productbuild", "--distribution", xml, "--package-path", temp, product], check=True)
        shutil.move(str(product), destination)
    return destination
