from pathlib import Path
import re
import sys
import zipfile

decoded = Path(sys.argv[1])
helper_dex = Path(sys.argv[2])

matches = list(decoded.glob("smali*/com/tempmail/data/services/AutoFillAccessibilityService.smali"))
if not matches:
    raise SystemExit("AutoFillAccessibilityService smali was not found")

hook = "invoke-static {p0, ROOT}, Lcom/tempmail/data/services/FloatingOtpAssistant;->attach(Landroid/accessibilityservice/AccessibilityService;Landroid/view/View;)V"
patched = False
for file in matches:
    text = file.read_text(encoding="utf-8")
    declaration = re.search(r"(?m)^\.method [^\n]*\bm48461w\([^\n]*$", text)
    if not declaration:
        continue
    start = declaration.start()
    end = text.find(".end method", start)
    if end < 0:
        continue
    method = text[start:end]
    if "FloatingOtpAssistant;->attach" in method:
        patched = True
        break
    invoke = method.find("->m49870j(")
    if invoke < 0:
        continue
    result = re.search(r"\n\s*move-result-object (v\d+)", method[invoke:])
    if not result:
        continue
    root_register = result.group(1)
    insert_at = invoke + result.end()
    patched_method = method[:insert_at] + "\n    " + hook.replace("ROOT", root_register) + method[insert_at:]
    text = text[:start] + patched_method + text[end:]
    file.write_text(text, encoding="utf-8")
    patched = True
    break

if not patched:
    raise SystemExit("Could not patch the existing autofill overlay method")

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

