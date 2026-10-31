# Read Aloud (Hindi + English)

Native Android app (Kotlin, no AndroidX/Compose) that reads PDFs (including scanned ones), images, DOCX and TXT files aloud with Android's text-to-speech in Hindi or English. Built to cope with very large books (1000+ pages).

## Build
1. Install Android Studio (Koala or newer) and open this folder.
2. Let it sync (Gradle 8.7, AGP 8.5.2, pdfbox-android, ML Kit text recognition).
3. Run on a phone, or Build > Build APK(s). Release builds are shrunk (R8 + resource shrinking).
   A GitHub Actions workflow is in `.github/workflows/android.yml`.

## Use
- **Open file**: PDF, image (JPG/PNG/WebP), DOCX or TXT (or "Open with" from a file manager).
- **Play / Pause / Stop** and a speed slider (0.5x–2.0x). Reading continues with the screen off; the notification has Play/Pause and Stop buttons.
- **Prev / Next / Go to page** to move around the book. The current page text is shown on screen.
- **Auto / हिंदी / English**: Auto picks Hindi (Devanagari) or English per passage. Choose installed voices separately for each language. **Voice data** opens system TTS settings to install Hindi/English voice data.
- **Always use OCR**: forces OCR on every PDF page (for Hindi PDFs whose text comes out garbled because of legacy fonts).
- The app remembers your page and position, and reopens the last book on launch.

## How it handles big books
- Nothing is loaded all at once. Pages are read one at a time; the next 2 pages are prepared in the background while the current one is spoken.
- Each page is checked: pages with a text layer are read directly, scanned pages are rendered and OCR'd on-device (ML Kit, Hindi + English, offline).
- Page text (including OCR results) is cached on the phone, so each page is only scanned once and re-reading is instant.
- The app keeps the 3 most recently opened books in its private storage (a copy of the file plus the cache); older ones are removed automatically.

## Limits
- OCR takes a second or two per page. Jumping far ahead in a scanned book means a few seconds' wait for that page. On a slow phone, or at a high speed setting, reading can catch up with OCR and pause briefly on "Preparing page…".
- Accuracy depends on scan quality; handwriting is not supported.
- Old `.doc` files are not supported; save as `.docx`.
- Pasting text is not supported in this version.
- If another app takes over audio (a call, music), reading is not paused automatically.
