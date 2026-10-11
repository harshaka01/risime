# 076: PDF export: Android PdfDocument plus an ActualText post-pass (no new dependency)

Date: 2026-10-11. Context: contract v1.34 §33 (PDF export on the phone), NEXT-PHASE E gate (Sinhala and Tamil
PDFs checked with pdftotext).

## Decision
- PDFs are made on the phone with Android's built-in `PdfDocument`, drawing each word as a single-script run in
  embedded Noto Sans, Noto Sans Sinhala and Noto Sans Tamil subsets (OFL; ~524 KB; licences under Settings → About).
- A small post-pass (`PdfPost`) adds `/ActualText` to every text object and writes the document info (Title,
  Producer, CreationDate; no Author).
- No PDF library is added.

## Why
`PdfDocument` alone draws Sinhala/Tamil correctly but its text layer is wrong: `pdftotext` lost conjuncts
(`ශ්‍රී` → `ශ`), reordered vowel signs, Tamil mostly empty, even Latin ligatures ("sign-off" → "sign-o"). With
`/ActualText` the text extracts exactly (`ශ්‍රී ලංකා`, `க்ஷேத்திர`), `pdffonts` shows the three fonts embedded,
on redroid 14, in both font paths (API 29+ and the API 26–28 script runs). Test: `PdfScriptsDeviceTest`.
