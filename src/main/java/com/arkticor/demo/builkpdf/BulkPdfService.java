package com.arkticor.demo.builkpdf;

import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;

/**
 * Generates a multi-page PDF by stamping values at the field coordinates recorded by {@link
 * PdfTemplateCache}. No AcroForm filling, no flatten().
 *
 * <p>Pages are generated in parallel chunks (one sub-document per worker), then the small number of
 * chunks is merged once, streamed straight to the response OutputStream with disk-backed buffering.
 */
@Service
public class BulkPdfService {

  /** Field values for one output page-set: field name -> display value. */
  public record PageData(Map<String, String> values) {}

  private static final float MIN_FONT_SIZE = 6f;
  private static final float CELL_PADDING_X = 2f;

  private final PdfTemplateCache template;
  private final ExecutorService pool =
      Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));

  public BulkPdfService(PdfTemplateCache template) {
    this.template = template;
  }

  /**
   * Generates the combined PDF for all pages and writes it to {@code target}. Page order matches
   * the order of {@code pages}.
   */
  public void generate(List<PageData> pages, OutputStream target) throws IOException {
    if (pages.isEmpty()) {
      throw new IllegalArgumentException("No pages requested");
    }

    int workers = Math.max(2, Runtime.getRuntime().availableProcessors());
    int chunkSize = Math.ceilDiv(pages.size(), workers);

    List<Future<byte[]>> futures = new ArrayList<>();
    for (int i = 0; i < pages.size(); i += chunkSize) {
      List<PageData> slice = pages.subList(i, Math.min(i + chunkSize, pages.size()));
      futures.add(pool.submit(() -> buildChunk(slice)));
    }

    PDFMergerUtility merger = new PDFMergerUtility();
    try {
      for (Future<byte[]> f : futures) { // iteration order = page order
        merger.addSource(new RandomAccessReadBuffer(f.get()));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("PDF generation interrupted", e);
    } catch (java.util.concurrent.ExecutionException e) {
      throw new IOException("PDF chunk generation failed", e.getCause());
    }
    merger.setDestinationStream(target);
    // Spill merge buffers to temp files instead of heap.
    merger.mergeDocuments(IOUtils.createTempFileOnlyStreamCache());
  }

  /** Builds one sub-document containing the pages for {@code slice}. */
  private byte[] buildChunk(List<PageData> slice) throws IOException {
    try (PDDocument templateDoc = Loader.loadPDF(template.strippedTemplateBytes());
        PDDocument out = new PDDocument()) {

      PDFont font = createFont(out);
      int templatePages = template.templatePageCount();

      for (PageData data : slice) {
        // Import every template page for this record, remember them by index.
        PDPage[] importedPages = new PDPage[templatePages];
        for (int p = 0; p < templatePages; p++) {
          importedPages[p] = out.importPage(templateDoc.getPage(p));
        }
        stampValues(out, importedPages, font, data.values());
      }

      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      out.save(bos); // must save while templateDoc is still open
      return bos.toByteArray();
    }
  }

  private void stampValues(PDDocument out, PDPage[] pages, PDFont font, Map<String, String> values)
      throws IOException {
    // One content stream per page of this record.
    for (int p = 0; p < pages.length; p++) {
      final int pageIndex = p;
      List<PdfTemplateCache.FieldPos> pageFields =
          template.fields().stream().filter(f -> f.pageIndex() == pageIndex).toList();
      if (pageFields.isEmpty()) continue;

      try (PDPageContentStream cs =
          new PDPageContentStream(
              out, pages[p], PDPageContentStream.AppendMode.APPEND, true, true)) {
        cs.setNonStrokingColor(0f, 0f, 0f);
        for (PdfTemplateCache.FieldPos field : pageFields) {
          String value = values.get(field.name());
          if (value == null || value.isBlank()) continue;
          switch (field.type()) {
            case CHECKBOX -> stampCheckbox(cs, font, field, value);
            case TEXT -> stampText(cs, font, field, value);
          }
        }
      }
    }
  }

  private void stampText(
      PDPageContentStream cs, PDFont font, PdfTemplateCache.FieldPos field, String value)
      throws IOException {
    String text = sanitizeForFont(font, value);
    if (text.isEmpty()) return;

    PDRectangle rect = field.rect();
    float fontSize = field.fontSize();
    float maxWidth = rect.getWidth() - 2 * CELL_PADDING_X;

    // Shrink to fit, mimicking AcroForm auto-size behaviour.
    float textWidth = stringWidth(font, text, fontSize);
    while (textWidth > maxWidth && fontSize > MIN_FONT_SIZE) {
      fontSize -= 0.5f;
      textWidth = stringWidth(font, text, fontSize);
    }

    // Approximate vertical centering of the text body within the rect.
    float y = rect.getLowerLeftY() + (rect.getHeight() - fontSize * 0.7f) / 2f;

    cs.beginText();
    cs.setFont(font, fontSize);
    cs.newLineAtOffset(rect.getLowerLeftX() + CELL_PADDING_X, y);
    cs.showText(text);
    cs.endText();
  }

  private void stampCheckbox(
      PDPageContentStream cs, PDFont font, PdfTemplateCache.FieldPos field, String value)
      throws IOException {
    boolean checked =
        "true".equalsIgnoreCase(value)
            || "yes".equalsIgnoreCase(value)
            || "on".equalsIgnoreCase(value)
            || "1".equals(value);
    if (!checked) return;

    PDRectangle rect = field.rect();
    float size = Math.max(MIN_FONT_SIZE, rect.getHeight() * 0.8f);
    float markWidth = stringWidth(font, "X", size);
    float x = rect.getLowerLeftX() + (rect.getWidth() - markWidth) / 2f;
    float y = rect.getLowerLeftY() + (rect.getHeight() - size * 0.7f) / 2f;

    cs.beginText();
    cs.setFont(font, size);
    cs.newLineAtOffset(x, y);
    cs.showText("X");
    cs.endText();
  }

  private static float stringWidth(PDFont font, String text, float fontSize) throws IOException {
    return font.getStringWidth(text) / 1000f * fontSize;
  }

  /**
   * Helvetica covers WinAnsi only. For unicode values (e.g. Chinese), embed a TTF instead — load it
   * ONCE per output document:
   *
   * <p>try (InputStream is = fontResource.getInputStream()) { return PDType0Font.load(doc, is,
   * true); // true = subset }
   */
  protected PDFont createFont(PDDocument doc) {
    return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
  }

  /** Drops characters the font cannot encode so showText() never throws. */
  private static String sanitizeForFont(PDFont font, String value) {
    StringBuilder sb = new StringBuilder(value.length());
    value
        .codePoints()
        .forEach(
            cp -> {
              String s = new String(Character.toChars(cp));
              try {
                font.encode(s);
                sb.append(s);
              } catch (IOException | IllegalArgumentException e) {
                sb.append('?');
              }
            });
    return sb.toString();
  }

  @PreDestroy
  void shutdown() {
    pool.shutdown();
  }
}
