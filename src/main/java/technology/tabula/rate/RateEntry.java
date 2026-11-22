package technology.tabula.rate;

import java.time.LocalDate;

/**
 * A single row of the external rate CSV.
 *
 * <p>The validity interval is half-open: it includes {@link #getEffective()}
 * and excludes {@link #getExpiry()}. A {@code null} expiry means the rate is
 * valid indefinitely.</p>
 */
public final class RateEntry {

    private final RateKey key;
    private final LocalDate effective;
    private final LocalDate expiry;
    private final String rate;
    private final int csvLine;

    public RateEntry(RateKey key, LocalDate effective, LocalDate expiry, String rate, int csvLine) {
        this.key = key;
        this.effective = effective;
        this.expiry = expiry;
        this.rate = rate;
        this.csvLine = csvLine;
    }

    public RateKey getKey() {
        return key;
    }

    /** First day on which this rate applies (inclusive). */
    public LocalDate getEffective() {
        return effective;
    }

    /** First day on which this rate no longer applies (exclusive), or {@code null}. */
    public LocalDate getExpiry() {
        return expiry;
    }

    /** Rate as written in the CSV, trimmed but otherwise unmodified. */
    public String getRate() {
        return rate;
    }

    /** 1-based physical line number of the record in the rate CSV. */
    public int getCsvLine() {
        return csvLine;
    }

    /** True when {@code date} falls inside the half-open validity interval. */
    public boolean isValidOn(LocalDate date) {
        if (date == null || date.isBefore(effective)) {
            return false;
        }
        return expiry == null || date.isBefore(expiry);
    }

    @Override
    public String toString() {
        return "RateEntry[key=" + key + ", effective=" + effective
                + ", expiry=" + (expiry == null ? "" : expiry)
                + ", rate=" + rate + ", line=" + csvLine + "]";
    }
}
