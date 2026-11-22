package technology.tabula.rate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.StringReader;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import technology.tabula.RectangularTextContainer;
import technology.tabula.Table;
import technology.tabula.TextChunk;
import technology.tabula.TextElement;
import technology.tabula.extractors.ExtractionAlgorithm;

public class TestRateTable {

    private static final String SOURCE = "rates.csv";

    private static RateTable load(String csv) throws Exception {
        return RateTable.load(new StringReader(csv), SOURCE);
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - //
    // Interval semantics

    @Test
    public void effectiveDateIsInclusiveAndExpiryIsExclusive() throws Exception {
        RateTable table = load(
                "线路,计费档,生效日,失效日,费率\n"
                        + "SZX-PEK,1,2024-01-01,2024-02-01,10\n"
                        + "SZX-PEK,1,2024-02-01,,12\n");
        RateIndex index = table.indexFor(Arrays.asList("线路", "计费档", "运送日期"));
        assertNotNull(index);

        RateKey key = new RateKey(Arrays.asList("SZX-PEK", "1"));

        RateEntry onStart = index.find(key, LocalDate.parse("2024-01-01"));
        assertNotNull(onStart);
        assertEquals("10", onStart.getRate());

        RateEntry onEnd = index.find(key, LocalDate.parse("2024-02-01"));
        assertNotNull(onEnd);
        assertEquals("12", onEnd.getRate()); // previous interval excludes its expiry

        RateEntry lastDay = index.find(key, LocalDate.parse("2024-01-31"));
        assertEquals("10", lastDay.getRate());

        assertNull(index.find(key, LocalDate.parse("2023-12-31")));
    }

    @Test
    public void emptyExpiryMeansValidForever() throws Exception {
        RateTable table = load("线路,计费档,生效日,失效日,费率\nA,1,2024-01-01,,10\n");
        RateIndex index = table.indexFor(Arrays.asList("线路", "计费档"));

        RateEntry entry = index.find(new RateKey(Arrays.asList("A", "1")), LocalDate.parse("2099-12-31"));
        assertNotNull(entry);
        assertEquals("10", entry.getRate());
        assertNull(entry.getExpiry());
    }

    @Test
    public void adjacentIntervalsAreAllowed() throws Exception {
        RateTable table = load(
                "线路,计费档,生效日,失效日,费率\n"
                        + "A,1,2024-01-01,2024-02-01,10\n"
                        + "A,1,2024-02-01,2024-03-01,11\n"
                        + "A,1,2024-03-01,,12\n");
        RateIndex index = table.indexFor(Arrays.asList("线路", "计费档"));
        assertEquals(3, index.entries().size());
    }

    @Test
    public void overlappingIntervalsAreRejectedAtLoadTime() {
        // No table is joined here: a bad rate CSV must fail on its own.
        try {
            load("线路,计费档,生效日,失效日,费率\n"        // line 1
                    + "A,1,2024-01-01,2024-03-01,10\n"   // line 2
                    + "A,1,2024-02-01,,11\n");           // line 3
            fail("expected RateCsvException");
        } catch (Exception e) {
            assertTrue(e instanceof RateCsvException);
            assertEquals(3, ((RateCsvException) e).getLineNumber());
        }
    }

    @Test
    public void overlappingIntervalsReportCsvLineNumber() throws Exception {
        try {
            RateTable table = load(
                    "线路,计费档,生效日,失效日,费率\n"        // line 1
                            + "A,1,2024-01-01,2024-03-01,10\n"   // line 2
                            + "A,1,2024-02-01,,11\n");           // line 3
            table.indexFor(Arrays.asList("线路", "计费档"));
            fail("expected RateCsvException");
        } catch (RateCsvException e) {
            assertEquals(3, e.getLineNumber());
            assertTrue(e.getMessage().contains("line 3"));
        }
    }

    @Test
    public void invalidIntervalReportsCsvLineNumber() throws Exception {
        try {
            load("线路,计费档,生效日,失效日,费率\n"
                    + "A,1,2024-03-01,2024-03-01,10\n");
            fail("expected RateCsvException");
        } catch (RateCsvException e) {
            assertEquals(2, e.getLineNumber());
        }
    }

    @Test
    public void overlapOnTheJoinKeyIsDetectedEvenIfExtraCsvColumnsDiffer() throws Exception {
        // '承运商' is a rate-CSV column the waybill table does not have, so the
        // records do not clash on the full CSV key but do clash once joined on
        // 线路 + 计费档.
        RateTable table = load(
                "线路,计费档,承运商,生效日,失效日,费率\n"
                        + "A,1,X,2024-01-01,2024-07-01,10\n"
                        + "A,1,Y,2024-05-01,,12\n");
        try {
            table.indexFor(Arrays.asList("线路", "计费档", "运送日期"));
            fail("expected RateCsvException");
        } catch (RateCsvException e) {
            assertEquals(3, e.getLineNumber());
        }
    }

    @Test
    public void invalidDateFormatReportsCsvLineNumber() throws Exception {
        try {
            load("线路,计费档,生效日,失效日,费率\n"
                    + "A,1,2024/01/01,,10\n");
            fail("expected RateCsvException");
        } catch (RateCsvException e) {
            assertEquals(2, e.getLineNumber());
            assertTrue(e.getMessage().contains("2024/01/01"));
        }
    }

    @Test
    public void missingRateColumnIsRejected() {
        try {
            load("线路,计费档,生效日\nA,1,2024-01-01\n");
            fail("expected RateCsvException");
        } catch (Exception e) {
            assertTrue(e instanceof RateCsvException);
            assertEquals(1, ((RateCsvException) e).getLineNumber());
        }
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - //
    // Indexing

    @Test
    public void indexIsBuiltOncePerLayout() throws Exception {
        RateTable table = load("线路,计费档,生效日,失效日,费率\nA,1,2024-01-01,,10\n");
        RateIndex first = table.indexFor(Arrays.asList("线路", "计费档", "运送日期"));
        RateIndex second = table.indexFor(Arrays.asList("id", "线路", "计费档", "运送日期"));
        assertSame(first, second);
    }

    @Test
    public void unmatchedHeadersProduceNoIndex() throws Exception {
        RateTable table = load("线路,计费档,生效日,失效日,费率\nA,1,2024-01-01,,10\n");
        assertNull(table.indexFor(Arrays.asList("单号", "重量")));
    }

    @Test
    public void joinsOnEverySharedKeyColumn() throws Exception {
        RateTable table = load(
                "线路,计费档,生效日,失效日,费率\n"
                        + "A,1,2024-01-01,,10\n"
                        + "A,2,2024-01-01,,20\n");
        RateIndex index = table.indexFor(Arrays.asList("线路", "计费档"));
        assertEquals("20", index.find(new RateKey(Arrays.asList("A", "2")), LocalDate.parse("2024-05-01")).getRate());
        assertNull(index.find(new RateKey(Arrays.asList("A", "3")), LocalDate.parse("2024-05-01")));
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - //
    // Join

    @Test
    public void joinAppendsEffectiveDateAndRateInOrder() throws Exception {
        RateTable table = load(
                "线路,计费档,生效日,失效日,费率\n"
                        + "SZX-PEK,1,2024-01-01,2024-07-01,10\n"
                        + "SZX-PEK,1,2024-07-01,,12\n");
        Table waybills = waybillTable(2);
        addRow(waybills, 1, "SZX-PEK", "1", "2024-06-30");
        addRow(waybills, 2, "SZX-PEK", "1", "2024-07-01");

        RateJoin.Result result = RateJoin.apply(Arrays.asList(waybills), table);
        assertTrue(result.getDiagnostics().isEmpty());

        List<List<RectangularTextContainer>> rows = waybills.getRows();
        assertEquals("生效日", rows.get(0).get(3).getText());
        assertEquals("费率", rows.get(0).get(4).getText());
        assertEquals("2024-01-01", rows.get(1).get(3).getText());
        assertEquals("10", rows.get(1).get(4).getText());
        assertEquals("2024-07-01", rows.get(2).get(3).getText());
        assertEquals("12", rows.get(2).get(4).getText());
        // original detail order and cells are untouched
        assertEquals("SZX-PEK", rows.get(1).get(0).getText());
        assertEquals("2024-06-30", rows.get(1).get(2).getText());
    }

    @Test
    public void rowsWithoutRateAreReportedWithPageAndRow() throws Exception {
        RateTable table = load("线路,计费档,生效日,失效日,费率\nSZX-PEK,1,2024-01-01,,10\n");
        Table waybills = waybillTable(2);
        addRow(waybills, 1, "SZX-PEK", "1", "2023-12-31"); // before validity
        addRow(waybills, 2, "SHA-CAN", "1", "2024-06-01"); // unknown lane
        addRow(waybills, 3, "SZX-PEK", "1", "not-a-date"); // invalid date

        RateJoin.Result result = RateJoin.apply(Arrays.asList(waybills), table);
        assertEquals(3, result.getDiagnostics().size());

        RateJoinDiagnostic first = result.getDiagnostics().get(0);
        assertEquals(2, first.getPageNumber());
        assertEquals(2, first.getRowNumber());
        assertEquals(RateJoinDiagnostic.Reason.NO_MATCHING_RATE, first.getReason());

        RateJoinDiagnostic third = result.getDiagnostics().get(2);
        assertEquals(2, third.getPageNumber());
        assertEquals(4, third.getRowNumber());
        assertEquals(RateJoinDiagnostic.Reason.INVALID_SHIPMENT_DATE, third.getReason());

        // all rows kept, with empty appended cells
        assertEquals(4, waybills.getRows().size());
        assertEquals("", waybills.getRows().get(1).get(3).getText());
        assertEquals("", waybills.getRows().get(1).get(4).getText());
    }

    @Test
    public void nonWaybillTableIsLeftUntouchedAndQuiet() throws Exception {
        RateTable table = load("线路,计费档,生效日,失效日,费率\nA,1,2024-01-01,,10\n");
        Table other = new Table(stubAlgorithm());
        addCell(other, 0, 0, "名称");
        addCell(other, 0, 1, "数量");
        addCell(other, 1, 0, "x");
        addCell(other, 1, 1, "1");

        RateJoin.Result result = RateJoin.apply(Arrays.asList(other), table);
        assertTrue(result.getDiagnostics().isEmpty());
        assertEquals(2, other.getRows().get(0).size());
    }

    @Test
    public void waybillTableWithoutSharedKeyIsReportedOnce() throws Exception {
        RateTable table = load("线路,计费档,生效日,失效日,费率\nA,1,2024-01-01,,10\n");
        Table waybills = new Table(stubAlgorithm());
        waybills.setPageNumber(3);
        addCell(waybills, 0, 0, "单号");
        addCell(waybills, 0, 1, "运送日期");
        addCell(waybills, 1, 0, "W1");
        addCell(waybills, 1, 1, "2024-05-01");

        RateJoin.Result result = RateJoin.apply(Arrays.asList(waybills), table);
        assertEquals(1, result.getDiagnostics().size());
        assertEquals(RateJoinDiagnostic.Reason.NO_SHARED_KEY_COLUMNS, result.getDiagnostics().get(0).getReason());
        assertEquals(3, result.getDiagnostics().get(0).getPageNumber());
        assertEquals(1, result.getDiagnostics().get(0).getRowNumber());
        assertEquals(2, waybills.getRows().get(0).size());
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - //
    // Helpers

    private static Table waybillTable(int pageNumber) {
        Table table = new Table(stubAlgorithm());
        table.setPageNumber(pageNumber);
        addCell(table, 0, 0, "线路");
        addCell(table, 0, 1, "计费档");
        addCell(table, 0, 2, "运送日期");
        return table;
    }

    private static void addRow(Table table, int row, String lane, String band, String date) {
        addCell(table, row, 0, lane);
        addCell(table, row, 1, band);
        addCell(table, row, 2, date);
    }

    private static void addCell(Table table, int row, int column, String text) {
        table.add(textChunk(text), row, column);
    }

    private static TextChunk textChunk(String text) {
        return new TextChunk(new TextElement(0f, 0f, 0f, 0f, null, 0f, text, 0f));
    }

    private static ExtractionAlgorithm stubAlgorithm() {
        return new ExtractionAlgorithm() {
            @Override
            public List<? extends Table> extract(technology.tabula.Page page) {
                return new ArrayList<>();
            }

            @Override
            public String toString() {
                return "stub";
            }
        };
    }
}
