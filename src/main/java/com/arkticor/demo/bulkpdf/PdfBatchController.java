package com.arkticor.demo.bulkpdf;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
public class PdfBatchController {

  private static final int MAX_IDS = 1000;

  private final BulkPdfService pdfService;
  private final MyDtoRepository repository; // your existing data access

  public PdfBatchController(BulkPdfService pdfService, MyDtoRepository repository) {
    this.pdfService = pdfService;
    this.repository = repository;
  }

  // curl -X POST localhost:8080/api/pdf/batch -H 'Content-Type: application/json' -d
  // '["1","2","3"]' -o batch.pdf
  @PostMapping(value = "/api/pdf/batch", produces = MediaType.APPLICATION_PDF_VALUE)
  public ResponseEntity<StreamingResponseBody> downloadBatch(@RequestBody List<String> ids) {

    if (ids == null || ids.isEmpty() || ids.size() > MAX_IDS) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected 1.." + MAX_IDS + " ids");
    }

    // ONE batched query (WHERE id IN (...)), not one query per id.
    // Make sure the repository preserves / restores the requested order.
    List<MyDto> dtos = repository.findAllByIdInOrder(ids);

    List<BulkPdfService.PageData> pages =
        dtos.stream().map(PdfBatchController::toPageData).toList();

    StreamingResponseBody body =
        outputStream -> {
          try {
            pdfService.generate(pages, outputStream);
          } catch (IOException e) {
            // Client likely disconnected or generation failed mid-stream.
            throw e;
          }
        };

    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"batch.pdf\"")
        .body(body);
  }

  /** Maps your DTO to template field names. Keys MUST match the PDF field names. */
  private static BulkPdfService.PageData toPageData(MyDto dto) {
    return new BulkPdfService.PageData(
        Map.of(
            "customerName", dto.customerName(),
            "accountId", dto.accountId(),
            "balance", dto.balanceFormatted(),
            "isActive", String.valueOf(dto.active()) // checkbox example
            ));
  }
}
