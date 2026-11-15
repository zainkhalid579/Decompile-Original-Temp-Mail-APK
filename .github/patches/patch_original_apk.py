from pathlib import Path
import re
import sys
import zipfile

decoded = Path(sys.argv[1])
helper_dex = Path(sys.argv[2])

services = list(decoded.glob("smali*/com/tempmail/data/services/AutoFillAccessibilityService.smali"))
if not services:
    raise SystemExit("AutoFillAccessibilityService smali was not found")

# Add a visible in-app toggle after MainActivity installs its content view. This
# makes the assistant discoverable without waiting for an autofill event.
activities = list(decoded.glob("smali*/com/tempmail/MainActivity.smali"))
if not activities:
    raise SystemExit("MainActivity smali was not found")
activity_patched = False
activity_hook = "invoke-static {p0, p0}, Lcom/tempmail/data/services/FloatingOtpAssistant;->attach(Landroid/content/Context;Ljava/lang/Object;)V"
for file in activities:
    text = file.read_text(encoding="utf-8")
    for declaration in re.finditer(r"(?ms)^\.method [^\n]*\n.*?^\.end method", text):
        method = declaration.group(0)
        if "onCreate(Landroid/os/Bundle;)V" not in method:
            continue
        if "FloatingOtpAssistant;->attach" in method:
            activity_patched = True
            break
        content = re.search(r"(?m)^\s*invoke-virtual\s+\{p0,[^}]+\},\s*L[^;]+;->setContentView\(Landroid/view/View;\)V\s*$", method)
        if content:
            insertion = declaration.start() + content.end()
            text = text[:insertion] + "\n    " + activity_hook + text[insertion:]
            file.write_text(text, encoding="utf-8")
            activity_patched = True
            break
    if activity_patched:
        break
if not activity_patched:
    raise SystemExit("Could not add the Floating OTP toggle after MainActivity.setContentView")

service_patched = False
service_hook = "invoke-static {p0, p1}, Lcom/tempmail/data/services/FloatingOtpAssistant;->attach(Landroid/content/Context;Ljava/lang/Object;)V"
for file in services:
    text = file.read_text(encoding="utf-8")
    for declaration in re.finditer(r"(?ms)^\.method [^\n]*\n.*?^\.end method", text):
        method = declaration.group(0)
        if "onAccessibilityEvent(Landroid/view/accessibility/AccessibilityEvent;)V" not in method:
            continue
        if "invoke-static {p0, p1}, Lcom/tempmail/data/services/FloatingOtpAssistant;->attach" in method:
            service_patched = True
            break
        offset = 0
        for line in method.splitlines(keepends=True):
            stripped = line.strip()
            if stripped and not stripped.startswith((".", ":", "#")):
                insertion = declaration.start() + offset
                text = text[:insertion] + "    " + service_hook + "\n" + text[insertion:]
                file.write_text(text, encoding="utf-8")
                service_patched = True
                break
            offset += len(line)
        if service_patched:
            break
    if service_patched:
        break
if not service_patched:
    raise SystemExit("Could not register the Temp Mail accessibility service from onAccessibilityEvent")

# APKTool rebuilds the original dex files. Add the helper DEX as a new multidex entry.
apk = decoded.parent / "temp-mail-unsigned.apk"
import subprocess
subprocess.run(["java", "-jar", str(decoded.parent / "apktool.jar"), "b", str(decoded), "-o", str(apk)], check=True)

merged = decoded.parent / "temp-mail-merged.apk"
with zipfile.ZipFile(apk, "r") as src, zipfile.ZipFile(merged, "w", zipfile.ZIP_DEFLATED) as dst:
    names = src.namelist()
    indices = []
    for name in names:
        match = re.fullmatch(r"classes(\d*)\.dex", name)
        if match:
            indices.append(int(match.group(1) or "1"))
    dex_name = f"classes{max(indices, default=1) + 1}.dex"
    for item in src.infolist():
        dst.writestr(item, src.read(item.filename))
    dst.write(helper_dex, dex_name)
print(f"Built patched Temp Mail APK with {dex_name} (original APK file left untouched).")

