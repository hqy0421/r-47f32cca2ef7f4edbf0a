package technology.tabula.rate;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * An external rate table loaded from a CSV file.
 *
 * <p>The CSV is expected to carry a header row. Columns named like
 * {@code 生效日}/{@code 失效日}/{@code 费率} (see {@link RateColumns}) describe
 * the validity interval and the rate itself; every other named column is a
 * potential join key (lane, billing band, ...). During a join only the key
 * columns that also appear in the extracted table's header are used, so the
 * rate CSV may carry extra columns without breaking the match.</p>
 *
 * <p>Validity intervals are half-open: {@code [effective, expiry)}. An empty
 * expiry means the rate never expires. Records of one key may be adjacent
 * ({@code expiry == next effective}) but must not overlap; invalid or
 * overlapping intervals are reported with their 1-based physical CSV line
 * number through {@link RateCsvException}.</p>
 *
 * <p>Lookups are served by a per-column-layout index built once, so joining a
 * document never re-scans the rate table per waybill row.</p>
 */
public final class RateTable {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final String sourceName;
    private final List<String> keyColumnNames;
    private final String effectiveColumnName;
    private final String rateColumnName;
    private final List<RateRecord> records;

    private final Map<String, RateIndex> indexCache = new HashMap<>();

    private RateTable(String sourceName, List<String> keyColumnNames,
                      String effectiveColumnName, String rateColumnName,
                      List<RateRecord> records) {
        this.sourceName = sourceName;
        this.keyColumnNames = Collections.unmodifiableList(new ArrayList<>(keyColumnNames));
        this.effectiveColumnName = effectiveColumnName;
        this.rateColumnName = rateColumnName;
        this.records = Collections.unmodifiableList(new ArrayList<>(records));
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - //
    // Loading

    public static RateTable load(File file) throws IOException, RateCsvException {
        return load(file, StandardCharsets.UTF_8);
    }

    public static RateTable load(File file, Charset charset) throws IOException, RateCsvException {
        try (Reader reader = new InputStreamReader(new FileInputStream(file), charset)) {
            return load(reader, file.getPath());
        }
    }

    public static RateTable load(Reader reader, String sourceName) throws IOException, RateCsvException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build();

        try (CSVParser parser = new CSVParser(reader, format)) {
            Iterator<CSVRecord> iterator = parser.iterator();

            if (!iterator.hasNext()) {
                throw new RateCsvException(sourceName, 1, "missing header row");
            }

            CSVRecord headerRecord = iterator.next();
            int headerLine = currentLine(parser);
            List<String> header = new ArrayList<>(headerRecord.size());
            for (int i = 0; i < headerRecord.size(); i++) {
                header.add(RateColumns.normalize(headerRecord.get(i)));
            }

            int effectiveIndex = -1;
            int expiryIndex = -1;
            int rateIndex = -1;
            List<Integer> keyIndexes = new ArrayList<>();
            List<String> keyNames = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();

            for (int i = 0; i < header.size(); i++) {
                String name = header.get(i);
                String normalized = RateColumns.normalizeName(name);
                if (normalized.isEmpty()) {
                    continue;
                }
                if (!seen.add(normalized)) {
                    throw new RateCsvException(sourceName, headerLine,
                            "duplicate column '" + name + "'");
                }
                if (RateColumns.isEffectiveColumn(name)) {
                    requireUnset("effective", effectiveIndex, sourceName, headerLine);
                    effectiveIndex = i;
                } else if (RateColumns.isExpiryColumn(name)) {
                    requireUnset("expiry", expiryIndex, sourceName, headerLine);
                    expiryIndex = i;
                } else if (RateColumns.isRateColumn(name)) {
                    requireUnset("rate", rateIndex, sourceName, headerLine);
                    rateIndex = i;
                } else if (!RateColumns.isReservedColumn(name)) {
                    keyIndexes.add(i);
                    keyNames.add(name);
                }
            }

            if (effectiveIndex < 0) {
                throw new RateCsvException(sourceName, headerLine,
                        "missing required column '" + RateColumns.EFFECTIVE + "'");
            }
            if (rateIndex < 0) {
                throw new RateCsvException(sourceName, headerLine,
                        "missing required column '" + RateColumns.RATE + "'");
            }
            if (keyIndexes.isEmpty()) {
                throw new RateCsvException(sourceName, headerLine,
                        "no join key columns: every column is reserved by the rate format");
            }

            List<RateRecord> records = new ArrayList<>();
            while (iterator.hasNext()) {
                CSVRecord record = iterator.next();
                int line = currentLine(parser);

                if (record.size() > header.size()) {
                    throw new RateCsvException(sourceName, line,
                            "expected at most " + header.size() + " columns but found " + record.size());
                }

                String effectiveText = cell(record, effectiveIndex);
                LocalDate effective = parseRequiredDate(effectiveText, RateColumns.EFFECTIVE, sourceName, line);

                String expiryText = cell(record, expiryIndex);
                LocalDate expiry = null;
                if (!expiryText.isEmpty()) {
                    expiry = parseRequiredDate(expiryText, RateColumns.EXPIRY, sourceName, line);
                    if (!expiry.isAfter(effective)) {
                        throw new RateCsvException(sourceName, line,
                                "invalid validity interval: " + RateColumns.EXPIRY + " " + expiry
                                        + " must be after " + RateColumns.EFFECTIVE + " " + effective);
                    }
                }

                String rate = cell(record, rateIndex);

                List<String> keyValues = new ArrayList<>(keyIndexes.size());
                for (Integer keyIndex : keyIndexes) {
                    keyValues.add(cell(record, keyIndex));
                }

                records.add(new RateRecord(keyValues, effective, expiry, rate, line));
            }

            validateIntervals(records, sourceName);

            return new RateTable(sourceName, keyNames, header.get(effectiveIndex), header.get(rateIndex), records);
        }
    }

    /**
     * Rejects overlapping intervals over the full key at load time, so a bad
     * rate CSV is reported even when no extracted table happens to join to it.
     * Interval validity (order of the two dates, format) is checked per record
     * while parsing.
     */
    private static void validateIntervals(List<RateRecord> records, String sourceName)
            throws RateCsvException {
        Map<RateKey, TreeMap<LocalDate, RateEntry>> byKey = new LinkedHashMap<>();
        for (RateRecord record : records) {
            RateKey key = new RateKey(record.keyValues);
            TreeMap<LocalDate, RateEntry> intervals = byKey.get(key);
            if (intervals == null) {
                intervals = new TreeMap<>();
                byKey.put(key, intervals);
            }
            RateEntry entry = new RateEntry(key, record.effective, record.expiry, record.rate, record.line);
            RateEntry sameStart = intervals.get(record.effective);
            if (sameStart != null) {
                throw overlap(sourceName, sameStart, entry);
            }
            RateEntry before = intervals.floorEntry(record.effective) == null
                    ? null : intervals.floorEntry(record.effective).getValue();
            if (before != null && (before.getExpiry() == null
                    || before.getExpiry().isAfter(record.effective))) {
                throw overlap(sourceName, before, entry);
            }
            intervals.put(record.effective, entry);
        }
    }

    private static RateCsvException overlap(String sourceName, RateEntry previous, RateEntry next) {
        return new RateCsvException(sourceName, next.getCsvLine(),
                "validity interval for key " + next.getKey() + " starting " + next.getEffective()
                        + " overlaps the record on line " + previous.getCsvLine());
    }

    private static void requireUnset(String role, int current, String sourceName, int line)
            throws RateCsvException {
        if (current >= 0) {
            throw new RateCsvException(sourceName, line, "duplicate " + role + " column");
        }
    }

    private static int currentLine(CSVParser parser) {
        long line = parser.getCurrentLineNumber();
        return line > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) line;
    }

    private static String cell(CSVRecord record, int index) {
        if (index < 0 || index >= record.size()) {
            return "";
        }
        return RateColumns.normalize(record.get(index));
    }

    private static LocalDate parseRequiredDate(String text, String column, String sourceName, int line)
            throws RateCsvException {
        if (text.isEmpty()) {
            throw new RateCsvException(sourceName, line,
                    "missing required value for column '" + column + "'");
        }
        try {
            return LocalDate.parse(text, DATE_FORMAT);
        } catch (DateTimeParseException e) {
            throw new RateCsvException(sourceName, line,
                    "invalid " + column + " '" + text + "' (expected YYYY-MM-DD)");
        }
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - //
    // Queries

    public String getSourceName() {
        return sourceName;
    }

    /** Rate-side key columns, in CSV order, excluding reserved columns. */
    public List<String> getKeyColumnNames() {
        return keyColumnNames;
    }

    /** Header text of the validity-start column, as written in the CSV. */
    public String getEffectiveColumnName() {
        return effectiveColumnName;
    }

    /** Header text of the rate column, as written in the CSV. */
    public String getRateColumnName() {
        return rateColumnName;
    }

    public int getRecordCount() {
        return records.size();
    }

    /**
     * Resolves the key columns that can be joined against {@code tableHeaders}
     * (matched by name, BOM/whitespace/case-insensitive) and returns a cached
     * validity index. Returns {@code null} when the table shares no key column
     * with the rate table, i.e. no join is possible.
     *
     * @throws RateCsvException if two records of a joined key overlap
     */
    public RateIndex indexFor(List<String> tableHeaders) throws RateCsvException {
        Map<String, Integer> tableIndexByName = new LinkedHashMap<>();
        for (int i = 0; i < tableHeaders.size(); i++) {
            String normalized = RateColumns.normalizeName(tableHeaders.get(i));
            if (!normalized.isEmpty() && !tableIndexByName.containsKey(normalized)) {
                tableIndexByName.put(normalized, i);
            }
        }

        List<Integer> selectedPositions = new ArrayList<>();
        List<String> joinColumns = new ArrayList<>();
        for (int i = 0; i < keyColumnNames.size(); i++) {
            String name = keyColumnNames.get(i);
            if (tableIndexByName.containsKey(RateColumns.normalizeName(name))) {
                selectedPositions.add(i);
                joinColumns.add(name);
            }
        }

        if (joinColumns.isEmpty()) {
            return null;
        }

        StringBuilder signature = new StringBuilder();
        for (String column : joinColumns) {
            signature.append(RateColumns.normalizeName(column)).append('\u0001');
        }
        String cacheKey = signature.toString();

        RateIndex cached = indexCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        RateIndex index = buildIndex(selectedPositions, joinColumns);
        indexCache.put(cacheKey, index);
        return index;
    }

    private RateIndex buildIndex(List<Integer> selectedPositions, List<String> joinColumns)
            throws RateCsvException {
        Map<RateKey, TreeMap<LocalDate, RateEntry>> byKey = new LinkedHashMap<>();

        for (RateRecord record : records) {
            List<String> values = new ArrayList<>(selectedPositions.size());
            for (Integer position : selectedPositions) {
                values.add(record.keyValues.get(position));
            }
            RateKey key = new RateKey(values);

            TreeMap<LocalDate, RateEntry> byEffective = byKey.get(key);
            if (byEffective == null) {
                byEffective = new TreeMap<>();
                byKey.put(key, byEffective);
            }

            RateEntry entry = new RateEntry(key, record.effective, record.expiry, record.rate, record.line);
            RateEntry previousWithSameStart = byEffective.get(record.effective);
            if (previousWithSameStart != null) {
                throw overlap(sourceName, previousWithSameStart, entry);
            }
            byEffective.put(record.effective, entry);
        }

        for (Map.Entry<RateKey, TreeMap<LocalDate, RateEntry>> group : byKey.entrySet()) {
            RateEntry previous = null;
            for (RateEntry entry : group.getValue().values()) {
                if (previous != null && overlaps(previous, entry)) {
                    throw overlap(sourceName, previous, entry);
                }
                previous = entry;
            }
        }

        return new RateIndex(joinColumns, byKey);
    }

    private static boolean overlaps(RateEntry previous, RateEntry next) {
        // previous is sorted before next by effective date.
        return previous.getExpiry() == null || previous.getExpiry().isAfter(next.getEffective());
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - //

    private static final class RateRecord {
        final List<String> keyValues;
        final LocalDate effective;
        final LocalDate expiry;
        final String rate;
        final int line;

        RateRecord(List<String> keyValues, LocalDate effective, LocalDate expiry, String rate, int line) {
            this.keyValues = keyValues;
            this.effective = effective;
            this.expiry = expiry;
            this.rate = rate;
            this.line = line;
        }
    }
}
