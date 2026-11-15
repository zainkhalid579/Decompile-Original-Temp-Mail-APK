from pathlib import Path
import re
import sys
import zipfile

decoded = Path(sys.argv[1])
helper_dex = Path(sys.argv[2])

matches = list(decoded.glob("smali*/com/tempmail/data/services/AutoFillAccessibilityService.smali"))
if not matches:
    raise SystemExit("AutoFillAccessibilityService smali was not found")

hook = "invoke-static {p0, ROOT}, Lcom/tempmail/data/services/FloatingOtpAssistant;->attach(Landroid/content/Context;Landroid/view/View;)V"
patched = False
for file in matches:
    text = file.read_text(encoding="utf-8")
    for declaration in re.finditer(r"(?ms)^\.method [^\n]*\n.*?^\.end method", text):
        method = declaration.group(0)
        if "Landroid/view/WindowManager;->addView(Landroid/view/View;" not in method:
            continue
        if "FloatingOtpAssistant;->attach" in method:
            patched = True
            break
        add = re.search(r"invoke-virtual\s+\{([^}]+)\},\s*Landroid/view/WindowManager;->addView\(Landroid/view/View;", method)
        if not add:
            continue
        registers = [part.strip() for part in add.group(1).split(",")]
        if len(registers) < 3:
            continue
        root_register = registers[1]
        measure = re.search(rf"(?m)^\s*invoke-virtual\s+\{{{re.escape(root_register)},[^}}]*\}},\s*Landroid/view/View;->measure\(II\)V\s*$", method)
        insertion = declaration.start() + (measure.start() if measure else add.start())
        text = text[:insertion] + "    " + hook.replace("ROOT", root_register) + "\n" + text[insertion:]
        file.write_text(text, encoding="utf-8")
        patched = True
        break
    if patched:
        break

if not patched:
    raise SystemExit("Could not patch the existing windowed autofill overlay")

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

