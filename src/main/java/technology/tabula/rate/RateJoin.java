package technology.tabula.rate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import technology.tabula.RectangularTextContainer;
import technology.tabula.Table;
import technology.tabula.TextChunk;
import technology.tabula.TextElement;

/**
 * Joins extracted tables to a {@link RateTable}.
 *
 * <p>For every extracted table the first row is the header. The shipment-date
 * column is located there by name; the join keys are the rate-CSV columns
 * whose names also occur in that header (for a waybill export typically
 * {@code 线路} and {@code 计费档}). Each detail row, in its original order,
 * receives two appended cells: the effective date of the matching rate
 * interval and the rate itself.</p>
 *
 * <p>The lookup is backed by {@link RateIndex}, which is built once per header
 * layout, so the rate table is never re-scanned for each waybill row.</p>
 *
 * <p>Tables that are not waybills (no shipment-date column) are left untouched
 * and produce no diagnostics; waybill tables whose header shares no key column
 * with the rate CSV are reported once.</p>
 */
public final class RateJoin {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private RateJoin() {
    }

    /**
     * Joins the waybill tables in {@code tables} in place and returns the
     * diagnostics collected along the way.
     *
     * @throws RateCsvException if the rate records for a joined key overlap
     */
    public static Result apply(List<Table> tables, RateTable rateTable) throws RateCsvException {
        if (tables == null || tables.isEmpty() || rateTable == null) {
            return new Result(Collections.<RateJoinDiagnostic>emptyList());
        }

        List<RateJoinDiagnostic> diagnostics = new ArrayList<>();
        for (Table table : tables) {
            joinTable(table, rateTable, diagnostics);
        }
        return new Result(Collections.unmodifiableList(diagnostics));
    }

    private static void joinTable(Table table, RateTable rateTable,
                                  List<RateJoinDiagnostic> diagnostics) throws RateCsvException {
        List<List<RectangularTextContainer>> rows = table.getRows();
        if (rows.isEmpty()) {
            return;
        }

        int pageNumber = table.getPageNumber();
        List<String> header = headerTexts(rows.get(0));

        int dateColumn = findShipmentDateColumn(header);
        if (dateColumn < 0) {
            // Not a waybill table (no shipment-date header). Leave it untouched
            // and stay quiet, so unrelated tables do not pollute stderr.
            return;
        }

        RateIndex index = rateTable.indexFor(header);
        if (index == null) {
            diagnostics.add(new RateJoinDiagnostic(pageNumber, 1,
                    RateJoinDiagnostic.Reason.NO_SHARED_KEY_COLUMNS,
                    "header shares no key column with rate CSV " + rateTable.getSourceName()));
            return;
        }

        List<Integer> keyColumns = new ArrayList<>(index.getJoinColumns().size());
        for (String keyName : index.getJoinColumns()) {
            int column = findColumn(header, keyName);
            if (column < 0) {
                // indexFor only selects names present in the header; guard anyway.
                diagnostics.add(new RateJoinDiagnostic(pageNumber, 1,
                        RateJoinDiagnostic.Reason.NO_SHARED_KEY_COLUMNS,
                        "header shares no key column with rate CSV " + rateTable.getSourceName()));
                return;
            }
            keyColumns.add(column);
        }

        // Append after every existing column, even if a detail row is wider
        // than the header, so no existing cell is overwritten.
        int firstAppendedColumn = Math.max(header.size(), table.getColCount());
        int lastAppendedColumn = firstAppendedColumn + 1;
        float appendedLeft = table.getRight();
        appendHeader(table, firstAppendedColumn, rateTable.getEffectiveColumnName(), appendedLeft);
        appendHeader(table, lastAppendedColumn, rateTable.getRateColumnName(), appendedLeft);

        for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
            List<RectangularTextContainer> row = rows.get(rowIndex);

            String dateText = cellText(row, dateColumn);
            LocalDate shipmentDate = parseDate(dateText);

            if (isBlank(row)) {
                // Keep the row (and its position) but there is nothing to look
                // up; the appended cells stay empty.
                continue;
            }

            String effectiveValue = "";
            String rateValue = "";
            if (shipmentDate == null) {
                diagnostics.add(new RateJoinDiagnostic(pageNumber, rowIndex + 1,
                        RateJoinDiagnostic.Reason.INVALID_SHIPMENT_DATE,
                        "invalid shipment date '" + dateText + "' (expected YYYY-MM-DD)"));
            } else {
                List<String> keyValues = new ArrayList<>(keyColumns.size());
                for (Integer keyColumn : keyColumns) {
                    keyValues.add(cellText(row, keyColumn));
                }
                RateKey key = new RateKey(keyValues);
                RateEntry entry = index.find(key, shipmentDate);
                if (entry == null) {
                    diagnostics.add(new RateJoinDiagnostic(pageNumber, rowIndex + 1,
                            RateJoinDiagnostic.Reason.NO_MATCHING_RATE,
                            "no matching rate for key " + key
                                    + " on " + DATE_FORMAT.format(shipmentDate)));
                } else {
                    effectiveValue = DATE_FORMAT.format(entry.getEffective());
                    rateValue = entry.getRate();
                }
            }

            appendCell(table, rowIndex, firstAppendedColumn, effectiveValue, row, appendedLeft);
            appendCell(table, rowIndex, lastAppendedColumn, rateValue, row, appendedLeft);
        }
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - //
    // Cells

    private static void appendHeader(Table table, int column, String text, float left) {
        RectangularTextContainer<?> anchor = table.getCell(0, 0);
        table.add(textChunk(text, anchor.getTop(), left), 0, column);
    }

    private static void appendCell(Table table, int rowIndex, int column, String text,
                                   List<RectangularTextContainer> row, float left) {
        if (text == null || text.isEmpty()) {
            // Columns are wide enough to be emitted as empty cells by
            // Table#getRows(), so the row alignment is preserved.
            return;
        }
        float top = rowTop(table, row);
        table.add(textChunk(text, top, left), rowIndex, column);
    }

    /**
     * Vertical anchor of the appended cell: the top of the first real cell in
     * the row, so geometric writers (JSON) keep the appended values on the row.
     */
    private static float rowTop(Table table, List<RectangularTextContainer> row) {
        for (RectangularTextContainer<?> cell : row) {
            if (cell != null && !cell.getText().isEmpty()) {
                return cell.getTop();
            }
        }
        return table.getTop();
    }

    private static TextChunk textChunk(String text, float top, float left) {
        return new TextChunk(new TextElement(top, left, 0f, 0f, null, 0f, text, 0f));
    }

    private static List<String> headerTexts(List<RectangularTextContainer> headerRow) {
        List<String> header = new ArrayList<>(headerRow.size());
        for (RectangularTextContainer<?> cell : headerRow) {
            header.add(cell == null ? "" : RateColumns.normalize(cell.getText()));
        }
        return header;
    }

    private static boolean isBlank(List<RectangularTextContainer> row) {
        for (RectangularTextContainer<?> cell : row) {
            if (cell != null && !cell.getText().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static String cellText(List<RectangularTextContainer> row, int column) {
        if (column < 0 || column >= row.size()) {
            return "";
        }
        RectangularTextContainer<?> cell = row.get(column);
        return cell == null ? "" : RateColumns.normalize(cell.getText());
    }

    private static LocalDate parseDate(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(text, DATE_FORMAT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static int findShipmentDateColumn(List<String> header) {
        for (int i = 0; i < header.size(); i++) {
            if (RateColumns.isShipmentDateColumn(header.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int findColumn(List<String> header, String name) {
        for (int i = 0; i < header.size(); i++) {
            if (RateColumns.sameName(header.get(i), name)) {
                return i;
            }
        }
        return -1;
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - //

    /** Outcome of a rate join. */
    public static final class Result {
        private final List<RateJoinDiagnostic> diagnostics;

        Result(List<RateJoinDiagnostic> diagnostics) {
            this.diagnostics = diagnostics;
        }

        public List<RateJoinDiagnostic> getDiagnostics() {
            return diagnostics;
        }

        public boolean hasDiagnostics() {
            return !diagnostics.isEmpty();
        }
    }
}
