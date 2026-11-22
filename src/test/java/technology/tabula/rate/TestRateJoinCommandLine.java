package technology.tabula.rate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.ParseException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import technology.tabula.CommandLineApp;

public class TestRateJoinCommandLine {

    private static final String CSV_HEADER = "LANE,BAND,SHIP_DATE";
    // Key columns are matched by name against the table header, so the ASCII
    // column names used in the generated PDF appear here too.
    private static final String RATE_HEADER = "LANE,BAND,生效日,失效日,费率";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private String run(String... args) throws ParseException {
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(CommandLineApp.buildOptions(), args);
        StringBuilder sb = new StringBuilder();
        new CommandLineApp(sb, cmd).extractTables(cmd);
        return sb.toString();
    }

    @Test
    public void defaultExportIsUnchangedWithoutRates() throws Exception {
        File pdf = writeWaybillPdf();
        String plain = run(pdf.getAbsolutePath(), "-p", "1", "-l", "-f", "CSV");

        assertTrue(plain.startsWith(CSV_HEADER));
        assertFalse(plain.contains("生效日"));
        assertEquals(3, plain.trim().split("\\r?\\n").length);
    }

    @Test
    public void ratesAreAppendedWithoutChangingDefaultOutput() throws Exception {
        File pdf = writeWaybillPdf();
        File rates = writeRates(
                RATE_HEADER + "\n"
                        + "SZX-PEK,1,2024-01-01,2024-07-01,10\n"
                        + "SZX-PEK,1,2024-07-01,,12\n"
                        + "SHA-CAN,2,2024-01-01,,20\n");

        String plain = run(pdf.getAbsolutePath(), "-p", "1", "-l", "-f", "CSV");
        String joined = run(pdf.getAbsolutePath(), "-p", "1", "-l", "-f", "CSV", "-R", rates.getAbsolutePath());

        String[] plainLines = plain.split("\\r?\\n");
        String[] joinedLines = joined.split("\\r?\\n");
        assertEquals(plainLines.length, joinedLines.length); // same row count

        assertEquals(CSV_HEADER + ",生效日,费率", joinedLines[0]);
        assertEquals("SZX-PEK,1,2024-06-30,2024-01-01,10", joinedLines[1]);
        assertEquals("SZX-PEK,1,2024-07-01,2024-07-01,12", joinedLines[2]);

        // The first three columns of every detail row are byte-identical to the
        // default export: only the joined columns are added.
        for (int i = 1; i < plainLines.length; i++) {
            assertTrue(joinedLines[i].startsWith(plainLines[i] + ","));
        }
    }

    @Test
    public void rowsWithoutRateKeepOrderAndAreReportedOnStderr() throws Exception {
        File pdf = writeWaybillPdf();
        File rates = writeRates(
                RATE_HEADER + "\n"
                        + "SZX-PEK,1,2024-01-01,2024-07-01,10\n"
                        + "SHA-CAN,2,2024-01-01,,20\n");

        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        java.io.PrintStream previous = System.err;
        String joined;
        try {
            System.setErr(new java.io.PrintStream(err, true, "UTF-8"));
            joined = run(pdf.getAbsolutePath(), "-p", "1", "-l", "-f", "CSV", "-R", rates.getAbsolutePath());
        } finally {
            System.setErr(previous);
        }

        String[] lines = joined.split("\\r?\\n");
        assertEquals("SZX-PEK,1,2024-07-01,,", lines[2]); // no match: empty appended cells
        String diagnostics = err.toString("UTF-8");
        assertTrue(diagnostics.contains("page 1, row 3"));
    }

    @Test(expected = ParseException.class)
    public void overlappingRateCsvIsRejectedWithLineNumber() throws Exception {
        File pdf = writeWaybillPdf();
        File rates = writeRates(
                RATE_HEADER + "\n"
                        + "SZX-PEK,1,2024-01-01,2024-07-01,10\n"
                        + "SZX-PEK,1,2024-05-01,,12\n");
        try {
            run(pdf.getAbsolutePath(), "-p", "1", "-l", "-f", "CSV", "-R", rates.getAbsolutePath());
        } catch (ParseException e) {
            assertTrue(e.getMessage().contains("line 3"));
            throw e;
        }
    }

    @Test
    public void missingRateCsvIsRejected() throws Exception {
        File pdf = writeWaybillPdf();
        try {
            run(pdf.getAbsolutePath(), "-p", "1", "-l", "-f", "CSV", "-R",
                    new File(folder.getRoot(), "absent.csv").getAbsolutePath());
        } catch (ParseException e) {
            assertTrue(e.getMessage().contains("does not exist"));
            return;
        }
        throw new AssertionError("expected ParseException");
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - //

    private File writeRates(String content) throws IOException {
        File file = folder.newFile("rates.csv");
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /**
     * Writes a one-page lattice table with two waybills. All characters are
     * plain ASCII so the standard Helvetica font can encode them.
     */
    private File writeWaybillPdf() throws IOException {
        File file = folder.newFile("waybills.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);

            float left = 50f;
            float top = 720f;
            float rowHeight = 20f;
            float width = 400f;
            String[][] cells = {
                    {"LANE", "BAND", "SHIP_DATE"},
                    {"SZX-PEK", "1", "2024-06-30"},
                    {"SZX-PEK", "1", "2024-07-01"},
            };

            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                for (int row = 0; row < cells.length; row++) {
                    float y = top - row * rowHeight;
                    cs.setLineWidth(0.7f);
                    cs.moveTo(left, y);
                    cs.lineTo(left + width, y);
                    cs.stroke();
                    for (int col = 0; col < cells[row].length; col++) {
                        cs.beginText();
                        cs.setFont(font, 9);
                        cs.newLineAtOffset(left + 5 + col * 110, y - 14);
                        cs.showText(cells[row][col]);
                        cs.endText();
                    }
                }
                cs.moveTo(left, top - cells.length * rowHeight);
                cs.lineTo(left + width, top - cells.length * rowHeight);
                cs.stroke();
                for (float x : new float[]{left, left + 110, left + 220, left + width}) {
                    cs.moveTo(x, top);
                    cs.lineTo(x, top - cells.length * rowHeight);
                }
                cs.stroke();
            }
            document.save(file);
        }
        return file;
    }
}
