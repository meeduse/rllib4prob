package fr.polytech.mnia.exploration.encoding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import de.hhu.stups.prob.translator.BAtom;
import de.hhu.stups.prob.translator.BBoolean;
import de.hhu.stups.prob.translator.BNumber;
import de.hhu.stups.prob.translator.BReal;
import de.hhu.stups.prob.translator.BRecord;
import de.hhu.stups.prob.translator.BSet;
import de.hhu.stups.prob.translator.BString;
import de.hhu.stups.prob.translator.BSymbolic;
import de.hhu.stups.prob.translator.BTuple;
import de.hhu.stups.prob.translator.BValue;
import de.hhu.stups.prob.translator.Translator;

/**
 * Exact canonicalisation for a validated subset of finite Classical B values.
 * The translator parses a B value; this class never evaluates B expressions.
 * Unsupported types fail loudly rather than compromise state equality.
 */
public final class CanonicalBValue {
    private CanonicalBValue() { }

    public static String encode(String evaluatedBExpression) {
        if (evaluatedBExpression == null) {
            throw new IllegalArgumentException("Null B value");
        }
        final BValue value;
        try {
            value = Translator.translate(evaluatedBExpression);
        } catch (Exception error) {
            throw new IllegalArgumentException("Cannot parse evaluated B value: "
                    + evaluatedBExpression, error);
        }
        return encodeValue(value);
    }

    private static String encodeValue(BValue value) {
        // Check these first even if a library version makes one a subtype
        // of a more general primitive representation.
        if (value instanceof BReal || value instanceof BSymbolic) {
            throw new UnsupportedOperationException(
                    "Exact encoding not established for " + value.getClass().getSimpleName());
        }
        if (value instanceof BNumber number) {
            return item("integer", number.bigIntegerValue().toString());
        }
        if (value instanceof BBoolean bool) {
            return item("boolean", Boolean.toString(bool.booleanValue()));
        }
        if (value instanceof BString string) {
            return item("string", string.stringValue());
        }
        if (value instanceof BAtom atom) {
            return item("atom", atom.stringValue());
        }
        if (value instanceof BTuple<?, ?> tuple) {
            return item("tuple", encodeValue(tuple.getFirst()), encodeValue(tuple.getSecond()));
        }
        if (value instanceof BSet<?> set) {
            // Sets, relations, functions, and sequences are mathematically sets
            // (a sequence is a function from 1..n). Sort the encoded members.
            List<String> members = new ArrayList<>();
            for (Object member : set.toSet()) {
                members.add(encodeValue((BValue) member));
            }
            members.sort(String::compareTo);
            return item("set", members.toArray(String[]::new));
        }
        if (value instanceof BRecord record) {
            Map<String, String> fields = new TreeMap<>();
            for (Map.Entry<?, ?> entry : record.toMap().entrySet()) {
                if (!(entry.getKey() instanceof String name)) {
                    throw new IllegalArgumentException("Non-string B record field: " + entry.getKey());
                }
                fields.put(name, encodeValue((BValue) entry.getValue()));
            }
            List<String> entries = new ArrayList<>();
            fields.forEach((name, fieldValue) -> entries.add(item("field", name, fieldValue)));
            return item("record", entries.toArray(String[]::new));
        }
        throw new UnsupportedOperationException(
                "Unsupported B value type: " + value.getClass().getName());
    }

    /** Length-prefixed components make the encoding unambiguous. */
    private static String item(String tag, String... fields) {
        StringBuilder out = new StringBuilder(tag).append('#').append(fields.length).append(':');
        for (String field : fields) {
            out.append(field.length()).append(':').append(field);
        }
        return out.toString();
    }
}
