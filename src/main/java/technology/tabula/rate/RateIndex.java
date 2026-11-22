package technology.tabula.rate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Index over the validity intervals of a {@link RateTable}, grouped by join
 * key.
 *
 * <p>Each key maps to its intervals sorted by effective date, so a lookup is a
 * single floor-entry probe instead of a scan over the rate table. Instances are
 * built once per table (and cached by {@link RateTable#indexFor(List)}), which
 * is what keeps a document-wide join from re-reading the rate CSV for every
 * waybill row.</p>
 */
public final class RateIndex {

    private final List<String> joinColumns;
    private final Map<RateKey, NavigableMap<LocalDate, RateEntry>> byKey;

    RateIndex(List<String> joinColumns, Map<RateKey, TreeMap<LocalDate, RateEntry>> byKey) {
        this.joinColumns = Collections.unmodifiableList(new ArrayList<>(joinColumns));
        Map<RateKey, NavigableMap<LocalDate, RateEntry>> copy = new LinkedHashMap<>();
        for (Map.Entry<RateKey, TreeMap<LocalDate, RateEntry>> entry : byKey.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableNavigableMap(entry.getValue()));
        }
        this.byKey = Collections.unmodifiableMap(copy);
    }

    /** Names of the rate columns that are joined against the extracted table. */
    public List<String> getJoinColumns() {
        return joinColumns;
    }

    public int getKeyCount() {
        return byKey.size();
    }

    /**
     * Finds the rate valid for {@code key} on {@code date}.
     *
     * @return the matching entry, or {@code null} when the key is unknown or
     *         no interval covers the date
     */
    public RateEntry find(RateKey key, LocalDate date) {
        if (key == null || date == null) {
            return null;
        }
        NavigableMap<LocalDate, RateEntry> intervals = byKey.get(key);
        if (intervals == null) {
            return null;
        }
        Map.Entry<LocalDate, RateEntry> candidate = intervals.floorEntry(date);
        if (candidate == null) {
            return null;
        }
        RateEntry entry = candidate.getValue();
        return entry.isValidOn(date) ? entry : null;
    }

    /** All entries, in rate-CSV order, for diagnostics and tests. */
    public List<RateEntry> entries() {
        List<RateEntry> entries = new ArrayList<>();
        for (NavigableMap<LocalDate, RateEntry> intervals : byKey.values()) {
            entries.addAll(intervals.values());
        }
        return entries;
    }
}
