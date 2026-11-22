package technology.tabula.rate;

import java.util.Locale;

/**
 * A problem encountered while joining one extracted row to the rate table,
 * reported with the PDF page and the row number inside the extracted table
 * (the header row is row 1, so detail rows start at row 2).
 */
public final class RateJoinDiagnostic {

    public enum Reason {
        /** The shipment date cell is empty or not a valid YYYY-MM-DD date. */
        INVALID_SHIPMENT_DATE,
        /** The table header shares no key column with the rate CSV. */
        NO_SHARED_KEY_COLUMNS,
        /** The key and date are valid but no rate interval covers them. */
        NO_MATCHING_RATE
    }

    private final int pageNumber;
    private final int rowNumber;
    private final Reason reason;
    private final String detail;

    public RateJoinDiagnostic(int pageNumber, int rowNumber, Reason reason, String detail) {
        this.pageNumber = pageNumber;
        this.rowNumber = rowNumber;
        this.reason = reason;
        this.detail = detail;
    }

    public int getPageNumber() {
        return pageNumber;
    }

    /** 1-based row number inside the extracted table (header row is 1). */
    public int getRowNumber() {
        return rowNumber;
    }

    public Reason getReason() {
        return reason;
    }

    public String getDetail() {
        return detail;
    }

    /**
     * Human-facing rendering used on stderr, e.g.
     * {@code page 2, row 7: no matching rate for key [SZX-PEK, 3] on 2024-05-01}.
     */
    @Override
    public String toString() {
        return String.format(Locale.ROOT, "page %d, row %d: %s", pageNumber, rowNumber, detail);
    }
}
