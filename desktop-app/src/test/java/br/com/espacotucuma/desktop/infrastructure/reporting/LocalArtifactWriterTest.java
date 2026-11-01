package br.com.espacotucuma.desktop.infrastructure.reporting;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalArtifactWriterTest {
  private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

  private Path temporaryDirectory;

  @BeforeEach
  void createWorkspaceTemporaryDirectory() throws Exception {
    temporaryDirectory =
        Files.createTempDirectory(Path.of("target"), "local-artifact-" + UUID.randomUUID());
  }

  @AfterEach
  void removeWorkspaceTemporaryDirectory() throws Exception {
    try (var paths = Files.walk(temporaryDirectory)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
    }
  }

  @Test
  void writesUtf8BomCsvWithQuotedAccentsAndFormulaInjectionProtection() throws Exception {
    Path destination = temporaryDirectory.resolve("relatorio.csv");

    LocalArtifactWriter.writeCsv(
        destination,
        List.of(
            List.of("Descrição", "Valor"),
            List.of("Mão de obra, hidratação", "=1+1"),
            List.of("Cliente \"Áurea\"", "-cmd"),
            List.of("Estorno", "-5,50")));

    byte[] content = Files.readAllBytes(destination);
    assertArrayEquals(
        new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf},
        new byte[] {content[0], content[1], content[2]});
    String csv =
        new String(content, 3, content.length - 3, java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(csv.contains("\"Descrição\",\"Valor\"\r\n"));
    assertTrue(csv.contains("\"Mão de obra, hidratação\",\"'=1+1\""));
    assertTrue(csv.contains("\"Cliente \"\"Áurea\"\"\",\"'-cmd\""));
    assertTrue(csv.contains("\"Estorno\",\"-5,50\""));
  }

  @Test
  void writesMultiPagePrintablePdfWithAccentedTextAndValidCrossReference() throws Exception {
    Path destination = temporaryDirectory.resolve("relatorio.pdf");
    List<String> lines = new java.util.ArrayList<>();
    lines.add("João, serviço de hidratação — R$ 125,50");
    for (int index = 0; index < 55; index++) lines.add("Linha " + index);

    LocalArtifactWriter.writePdf(destination, "Relatório de serviços", lines);

    String pdf = new String(Files.readAllBytes(destination), WINDOWS_1252);
    assertTrue(pdf.startsWith("%PDF-1.4"));
    assertTrue(pdf.contains("xref\n"));
    assertTrue(pdf.contains("/Count 2"));
    assertTrue(pdf.contains("Relat\u00f3rio de servi\u00e7os"));
    assertTrue(pdf.contains("Jo\u00e3o, servi\u00e7o de hidrata\u00e7\u00e3o"));
    assertTrue(pdf.endsWith("%%EOF\n"));
  }

  @Test
  void receiptAlwaysStatesItIsNonFiscalAndNotProofOfPayment() throws Exception {
    Path destination = temporaryDirectory.resolve("comprovante.pdf");

    LocalArtifactWriter.writeReceiptPdf(
        destination, List.of("Cliente: Áurea", "Total do serviço: R$ 95,00"));

    String pdf = new String(Files.readAllBytes(destination), WINDOWS_1252);
    assertTrue(pdf.contains("N\u00c3O FISCAL"));
    assertTrue(pdf.contains("Comprovante interno de atendimento. N\u00c3O FISCAL."));
    assertTrue(pdf.contains("N\u00e3o \u00e9 nota fiscal nem comprovante de pagamento."));
    assertTrue(pdf.contains("Total do servi\u00e7o: R$ 95,00"));
  }

  @Test
  void failedWriteLeavesExistingDestinationDirectoryContentsUntouched() throws Exception {
    Path destination = temporaryDirectory.resolve("relatorio.csv");
    Files.createDirectory(destination);
    Path sentinel = destination.resolve("preservar.txt");
    Files.writeString(sentinel, "dados locais intactos");

    assertThrows(
        IOException.class,
        () -> LocalArtifactWriter.writeCsv(destination, List.of(List.of("nova exportação"))));

    assertEquals("dados locais intactos", Files.readString(sentinel));
  }
}
