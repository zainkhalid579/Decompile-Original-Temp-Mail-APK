from pathlib import Path
import shutil
import sys

root = Path(sys.argv[1])
service = root / "app/src/main/java/com/tempmail/data/services/AutoFillAccessibilityService.java"
helper_source = Path(__file__).with_name("FloatingOtpAssistant.java")
helper_target = root / "app/src/main/java/com/tempmail/data/services/FloatingOtpAssistant.java"

text = service.read_text(encoding="utf-8")
needle = "        RelativeLayout relativeLayout = autoFillAccessibilityService.f54979L;"
hook = "        FloatingOtpAssistant.attach(autoFillAccessibilityService, relativeLayoutM49870j, mailboxTable, accessibilityNodeInfo2);\n"
if hook.strip() not in text:
    if text.count(needle) != 1:
        raise SystemExit("Could not uniquely find the existing autofill overlay insertion point")
    text = text.replace(needle, hook + needle, 1)
    service.write_text(text, encoding="utf-8")

helper_target.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(helper_source, helper_target)

# JADX exports these decoded nine-patches as plain PNGs. Rename the affected
# AndroidX divider to a regular bitmap so AAPT can compile the reconstructed project.
for image in (root / "app/src/main/res").glob("drawable*/abc_list_divider_mtrl_alpha.9.png"):
    image.rename(image.with_name("abc_list_divider_mtrl_alpha.png"))

print("Attached OTP controls to the original Temp Mail autofill overlay.")

