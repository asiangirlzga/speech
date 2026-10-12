# Read Aloud (Hindi + English)

Tiny native Android app (Kotlin, no AndroidX/Compose) that opens PDF, DOCX and TXT files and reads them aloud with Android's text-to-speech in Hindi or English.

## Build
1. Install Android Studio (Koala or newer) and open this folder.
2. Let it sync (it downloads Gradle 8.7, AGP 8.5.2 and the single dependency, pdfbox-android).
3. Run on a phone, or Build > Build APK(s). For the smallest APK use a release build (R8 + resource shrinking are on).

## Use
- **Open file**: pick a PDF, DOCX or TXT (or use "Open with" from a file manager). You can also paste text.
- **Auto / हिंदी / English**: Auto detects Hindi (Devanagari) vs English per passage, so mixed documents switch voice automatically.
- **Hindi voice / English voice**: choose installed voices; "Default" uses the system voice.
- **Voice data**: opens system TTS settings to install Hindi or English voice data (e.g. Google Speech Services).
- **Play / Pause / Stop**, plus a speed slider (0.5x–2.0x).

## Limits
- Scanned (image-only) PDFs have no text and would need OCR, which is not included to keep the app light.
- Some Hindi PDFs use legacy fonts and extract as garbled text; DOCX and Unicode PDFs work best.
- Old `.doc` files are not supported; save as `.docx`.
- Playback stops when the app is closed (no background service, to keep it simple).
