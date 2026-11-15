from pathlib import Path
import re
import sys
import zipfile

decoded = Path(sys.argv[1])
helper_dex = Path(sys.argv[2])

matches = list(decoded.glob("smali*/com/tempmail/utils/AccessibilityHelpers.smali"))
if not matches:
    raise SystemExit("AccessibilityHelpers smali was not found")

layout_id = "0x7f0d001c"  # accessiblity_autofill from the original APK resources
hook = "invoke-static {p0, p1, ROOT}, Lcom/tempmail/data/services/FloatingOtpAssistant;->attach(Landroid/content/Context;Ljava/lang/String;Landroid/view/View;)V"
patched = False
for file in matches:
    text = file.read_text(encoding="utf-8")
    const = re.search(rf"(?m)^\s*const(?:/[^\s]+)?\s+(v\d+), {layout_id}\s*$", text)
    if not const:
        continue
    method_start = text.rfind(".method ", 0, const.start())
    method_end = text.find(".end method", const.end())
    if method_start < 0 or method_end < 0:
        continue
    method = text[method_start:method_end]
    if "FloatingOtpAssistant;->attach" in method:
        patched = True
        break
    result = re.search(rf"move-result-object {const.group(1)}\s*\n\s*check-cast {const.group(1)}, Landroid/widget/RelativeLayout;", method)
    if not result:
        continue
    root_register = const.group(1)
    insert_at = method_start + result.end()
    text = text[:insert_at] + "\n    " + hook.replace("ROOT", root_register) + text[insert_at:]
    file.write_text(text, encoding="utf-8")
    patched = True
    break

if not patched:
    raise SystemExit("Could not patch the original autofill overlay inflation point")

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

