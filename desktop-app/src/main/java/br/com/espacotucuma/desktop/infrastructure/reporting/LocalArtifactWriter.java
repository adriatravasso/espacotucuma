package br.com.espacotucuma.desktop.infrastructure.reporting;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Writes small offline CSV and printable PDF files without changing the source data. */
public final class LocalArtifactWriter {
  private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
  private static final byte[] UTF8_BOM = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf};

  private LocalArtifactWriter() {}

  public static void writeCsv(Path destination, List<List<String>> rows) throws IOException {
    Objects.requireNonNull(rows, "rows");
    StringBuilder csv = new StringBuilder();
    for (List<String> row : rows) {
      for (int index = 0; index < row.size(); index++) {
        if (index > 0) csv.append(',');
        csv.append(quoteCsv(safeSpreadsheetText(row.get(index))));
      }
      csv.append("\r\n");
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    bytes.write(UTF8_BOM);
    bytes.write(csv.toString().getBytes(StandardCharsets.UTF_8));
    writeAtomically(destination, bytes.toByteArray());
  }

  public static void writePdf(Path destination, String title, List<String> lines)
      throws IOException {
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(lines, "lines");
    List<String> content = new ArrayList<>();
    content.add(title);
    content.add("");
    for (String line : lines) {
      if (line == null || line.isEmpty()) {
        content.add("");
      } else {
        for (int start = 0; start < line.length(); start += 96) {
          content.add(line.substring(start, Math.min(start + 96, line.length())));
        }
      }
    }
    byte[] pdf = buildPdf(content);
    writeAtomically(destination, pdf);
  }

  public static void writeReceiptPdf(Path destination, List<String> details) throws IOException {
    List<String> receipt = new ArrayList<>();
    receipt.add("NÃO FISCAL");
    receipt.addAll(Objects.requireNonNull(details, "details"));
    receipt.add("");
    receipt.add("Comprovante interno de atendimento. NÃO FISCAL.");
    receipt.add("Não é nota fiscal nem comprovante de pagamento.");
    writePdf(destination, "COMPROVANTE NÃO FISCAL — ESPAÇO TUCUMÃ", receipt);
  }

  static String safeSpreadsheetText(String value) {
    String text = value == null ? "" : value;
    int first = 0;
    while (first < text.length() && Character.isWhitespace(text.charAt(first))) first++;
    if (first < text.length()
        && "=+-@".indexOf(text.charAt(first)) >= 0
        && !text.strip().matches("[-+]?(?:[0-9]+(?:[.,][0-9]*)?|[.,][0-9]+)")) {
      return "'" + text;
    }
    return text;
  }

  private static String quoteCsv(String value) {
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  private static byte[] buildPdf(List<String> lines) throws IOException {
    int linesPerPage = 50;
    int pageCount = Math.max(1, (lines.size() + linesPerPage - 1) / linesPerPage);
    List<byte[]> objects = new ArrayList<>();
    objects.add(null); // PDF object numbers start at 1.
    objects.add(ascii("<< /Type /Catalog /Pages 2 0 R >>"));
    StringBuilder kids = new StringBuilder();
    for (int page = 0; page < pageCount; page++) {
      kids.append(4 + page * 2).append(" 0 R ");
    }
    objects.add(ascii("<< /Type /Pages /Kids [" + kids + "] /Count " + pageCount + " >>"));
    objects.add(
        ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"));
    for (int page = 0; page < pageCount; page++) {
      int pageObject = 4 + page * 2;
      int contentObject = pageObject + 1;
      objects.add(
          ascii(
              "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents "
                  + contentObject
                  + " 0 R >>"));
      int from = page * linesPerPage;
      int to = Math.min(lines.size(), from + linesPerPage);
      StringBuilder stream = new StringBuilder("BT /F1 9 Tf 48 752 Td 12 TL\n");
      for (int index = from; index < to; index++) {
        stream.append('(').append(pdfText(lines.get(index))).append(") Tj T*\n");
      }
      stream.append("ET\n");
      ByteBuffer encoded = WINDOWS_1252.encode(stream.toString());
      byte[] streamBytes = new byte[encoded.remaining()];
      encoded.get(streamBytes);
      objects.add(
          concat(
              ascii("<< /Length " + streamBytes.length + " >>\nstream\n"),
              streamBytes,
              ascii("endstream")));
    }

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    output.write(ascii("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n"));
    int[] offsets = new int[objects.size()];
    for (int number = 1; number < objects.size(); number++) {
      offsets[number] = output.size();
      output.write(ascii(number + " 0 obj\n"));
      output.write(objects.get(number));
      output.write(ascii("\nendobj\n"));
    }
    int xrefOffset = output.size();
    output.write(ascii("xref\n0 " + objects.size() + "\n0000000000 65535 f \n"));
    for (int number = 1; number < objects.size(); number++) {
      output.write(
          ascii(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", offsets[number])));
    }
    output.write(
        ascii(
            "trailer\n<< /Size "
                + objects.size()
                + " /Root 1 0 R >>\nstartxref\n"
                + xrefOffset
                + "\n%%EOF\n"));
    return output.toByteArray();
  }

  private static String pdfText(String value) {
    StringBuilder escaped = new StringBuilder();
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character == '(' || character == ')' || character == '\\') escaped.append('\\');
      if (character < 32 || character > 255) escaped.append('?');
      else escaped.append(character);
    }
    return escaped.toString();
  }

  private static void writeAtomically(Path destination, byte[] bytes) throws IOException {
    Path target = Objects.requireNonNull(destination, "destination").toAbsolutePath().normalize();
    Path parent = target.getParent();
    if (parent == null) throw new IOException("Escolha uma pasta de destino válida.");
    Files.createDirectories(parent);
    if (Files.isSymbolicLink(target))
      throw new IOException("O arquivo de destino não pode ser um link.");
    Path temporary = Files.createTempFile(parent, ".tucuma-", ".tmp");
    try {
      Files.write(temporary, bytes);
      try {
        Files.move(
            temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.ISO_8859_1);
  }

  private static byte[] concat(byte[] first, byte[] second, byte[] third) throws IOException {
    ByteArrayOutputStream bytes =
        new ByteArrayOutputStream(first.length + second.length + third.length);
    bytes.write(first);
    bytes.write(second);
    bytes.write(third);
    return bytes.toByteArray();
  }
}
