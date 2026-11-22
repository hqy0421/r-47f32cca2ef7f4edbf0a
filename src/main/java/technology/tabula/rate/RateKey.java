package technology.tabula.rate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Composite key used to join an extracted waybill row to a rate record: the
 * values of the rate CSV columns that share their name with columns of the
 * extracted table (for example lane + billing band).
 */
public final class RateKey {

    private final List<String> values;

    public RateKey(List<String> values) {
        this.values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    public List<String> getValues() {
        return values;
    }

    public int size() {
        return values.size();
    }

    public String get(int index) {
        return values.get(index);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RateKey)) {
            return false;
        }
        return values.equals(((RateKey) other).values);
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
