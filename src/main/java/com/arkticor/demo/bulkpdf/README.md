# PDF coordinate-stamping implementation (PDFBox 3.x)

Replaces per-ID AcroForm fill + flatten + 1000-document merge with:
read field coordinates once at startup -> importPage + draw text per record
-> single merge of N (= CPU cores) chunks, streamed to the response.

## Dependency

```xml
<dependency>
    <groupId>org.apache.pdfbox</groupId>
    <artifactId>pdfbox</artifactId>
    <version>3.0.5</version>
</dependency>
```

## Files

- `PdfTemplateCache.java`  — startup: reads field names, types, page index,
  widget rectangles, and /DA font sizes from `classpath:templates/form.pdf`;
  caches a stripped (form-removed) copy of the template bytes in memory.
  The template file itself is never modified.
- `BulkPdfService.java`    — chunked parallel page generation + final merge
  with disk-backed buffering (`createTempFileOnlyStreamCache`).
- `PdfBatchController.java`— streams the result via `StreamingResponseBody`;
  shows the DTO -> field-name mapping point.

## Wire-up checklist

1. Adjust the template path in `PdfTemplateCache` if yours differs.
2. Replace `MyDto` / `MyDtoRepository` with your types; keep the data load
   as ONE `WHERE id IN (...)` query and preserve the requested id order.
3. Fill in `toPageData(...)` — keys must exactly match the field names set
   in the PDF template (case-sensitive, fully qualified names like
   `form1.customerName` if the template uses a hierarchy).
4. If field values can contain non-WinAnsi characters (Chinese, smart
   quotes, etc.), override `createFont(...)` to embed a TTF with
   `PDType0Font.load(doc, ttfStream, true)`. Helvetica will replace
   unsupported characters with `?` (see `sanitizeForFont`).

## Verifying coordinates visually (one-off debug)

If stamped text looks misplaced, render the field rectangles once:

```java
// inside stampValues, temporarily:
cs.addRect(rect.getLowerLeftX(), rect.getLowerLeftY(),
           rect.getWidth(), rect.getHeight());
cs.setStrokingColor(1f, 0f, 0f);
cs.stroke();
```

Generate one page, open it, and compare boxes vs. text. Tune the vertical
centering factor (`fontSize * 0.7f`) if your template's fields are tall.

## Tuning

- Chunk count = CPU cores by default. For very large templates per page,
  reduce parallelism to bound memory (each worker holds its chunk in a
  ByteArrayOutputStream until merge).
- If responses can still exceed your gateway timeout, switch to an async
  job: POST returns a job id, generation runs in the background, client
  downloads on completion.

## PDFBox 2.x equivalents (if you cannot use 3.x)

| 3.x                                          | 2.0.x                                  |
|----------------------------------------------|----------------------------------------|
| `Loader.loadPDF(bytes)`                      | `PDDocument.load(bytes)`               |
| `new PDType1Font(Standard14Fonts.FontName.HELVETICA)` | `PDType1Font.HELVETICA`      |
| `new RandomAccessReadBuffer(bytes)`          | `merger.addSource(new ByteArrayInputStream(bytes))` |
| `mergeDocuments(IOUtils.createTempFileOnlyStreamCache())` | `mergeDocuments(MemoryUsageSetting.setupTempFileOnly())` |
| `jakarta.annotation.*`                       | `javax.annotation.*`                   |