package technology.tabula.rate;

/**
 * Signals a problem in an external rate CSV (bad date, invalid or overlapping
 * validity interval, missing column, ...).
 *
 * <p>The exception carries the 1-based physical line number in the CSV so the
 * caller can point the user at the offending record.</p>
 */
@SuppressWarnings("serial")
public class RateCsvException extends Exception {

    private final String sourceName;
    private final int lineNumber;

    public RateCsvException(String sourceName, int lineNumber, String message) {
        super(format(sourceName, lineNumber, message));
        this.sourceName = sourceName;
        this.lineNumber = lineNumber;
    }

    public RateCsvException(String sourceName, int lineNumber, String message, Throwable cause) {
        super(format(sourceName, lineNumber, message), cause);
        this.sourceName = sourceName;
        this.lineNumber = lineNumber;
    }

    private static String format(String sourceName, int lineNumber, String message) {
        StringBuilder sb = new StringBuilder();
        if (sourceName != null && !sourceName.isEmpty()) {
            sb.append(sourceName).append(' ');
        }
        sb.append("line ").append(lineNumber).append(": ").append(message);
        return sb.toString();
    }

    public String getSourceName() {
        return sourceName;
    }

    /** 1-based line number in the CSV file. */
    public int getLineNumber() {
        return lineNumber;
    }
}
