package com.arkticor.demo.bulkpdf;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Loads the PDF form template ONCE at startup and pre-computes everything needed for coordinate
 * stamping:
 *
 * <p>1. For every terminal form field: its name, type, page index, widget rectangle, and the font
 * size parsed from its /DA string. 2. A "stripped" copy of the template (AcroForm + widget
 * annotations removed) so the empty interactive boxes don't render under the stamped text.
 *
 * <p>The original template file on the classpath is never modified.
 */
@Component
public class PdfTemplateCache {

  public enum FieldType {
    TEXT,
    CHECKBOX
  }

  /**
   * @param name fully qualified field name from the template
   * @param type TEXT or CHECKBOX
   * @param pageIndex 0-based page index within the template
   * @param rect widget rectangle in PDF user-space coordinates
   * @param fontSize font size from /DA, or a default if auto-size (0) / absent
   */
  public record FieldPos(
      String name, FieldType type, int pageIndex, PDRectangle rect, float fontSize) {}

  private static final Pattern DA_FONT_SIZE = Pattern.compile("([\\d.]+)\\s+Tf");
  private static final float DEFAULT_FONT_SIZE = 10f;

  private final Resource templateResource;

  private byte[] strippedTemplateBytes;
  private List<FieldPos> fields;
  private int templatePageCount;

  public PdfTemplateCache(@Value("classpath:templates/form.pdf") Resource templateResource) {
    this.templateResource = templateResource;
  }

  @PostConstruct
  void init() throws IOException {
    byte[] raw;
    try (InputStream is = templateResource.getInputStream()) {
      raw = is.readAllBytes(); // read from the jar exactly once
    }

    try (PDDocument doc = Loader.loadPDF(raw)) {
      PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
      if (form == null) {
        throw new IllegalStateException("Template has no AcroForm: " + templateResource);
      }

      this.templatePageCount = doc.getNumberOfPages();
      this.fields = Collections.unmodifiableList(extractFieldPositions(doc, form));

      // Strip interactivity from the IN-MEMORY copy only, then cache it.
      doc.getDocumentCatalog().setAcroForm(null);
      for (PDPage page : doc.getPages()) {
        page.setAnnotations(Collections.emptyList());
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length);
      doc.save(out);
      this.strippedTemplateBytes = out.toByteArray();
    }
  }

  private List<FieldPos> extractFieldPositions(PDDocument doc, PDAcroForm form) {
    // Map each page object to its index so widgets can be located fast.
    Map<PDPage, Integer> pageIndexByPage = new IdentityHashMap<>();
    int idx = 0;
    for (PDPage page : doc.getPages()) {
      pageIndexByPage.put(page, idx++);
    }

    String formDefaultDa = form.getCOSObject().getString(COSName.DA);
    List<FieldPos> result = new ArrayList<>();

    for (PDField field : form.getFieldTree()) {
      if (!(field instanceof PDTerminalField terminal)) {
        continue; // non-terminal nodes have no widgets of their own
      }
      FieldType type = (terminal instanceof PDCheckBox) ? FieldType.CHECKBOX : FieldType.TEXT;
      float fontSize = parseFontSize(terminal.getCOSObject().getString(COSName.DA), formDefaultDa);

      for (PDAnnotationWidget widget : terminal.getWidgets()) {
        PDPage widgetPage = widget.getPage();
        // /P entry can be absent in some authoring tools; fall back to page 0.
        int pageIndex = (widgetPage != null) ? pageIndexByPage.getOrDefault(widgetPage, 0) : 0;
        result.add(
            new FieldPos(
                terminal.getFullyQualifiedName(),
                type,
                pageIndex,
                widget.getRectangle(),
                fontSize));
      }
    }
    return result;
  }

  /** Parses "/Helv 10 Tf 0 g" -> 10. Auto-size (0 Tf) falls back to default. */
  private float parseFontSize(String fieldDa, String formDa) {
    for (String da : new String[] {fieldDa, formDa}) {
      if (da == null) continue;
      Matcher m = DA_FONT_SIZE.matcher(da);
      if (m.find()) {
        float size = Float.parseFloat(m.group(1));
        if (size > 0) return size;
      }
    }
    return DEFAULT_FONT_SIZE;
  }

  /** Template bytes with AcroForm and widgets removed. Thread-safe to share. */
  public byte[] strippedTemplateBytes() {
    return strippedTemplateBytes;
  }

  public List<FieldPos> fields() {
    return fields;
  }

  public int templatePageCount() {
    return templatePageCount;
  }
}
