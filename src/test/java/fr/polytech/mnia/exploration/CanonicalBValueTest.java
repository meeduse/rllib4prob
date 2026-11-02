package fr.polytech.mnia.exploration;

import fr.polytech.mnia.exploration.encoding.CanonicalBValue;

/** Basic checks for the normalization rules used by state identity. */
public final class CanonicalBValueTest {
    private CanonicalBValueTest() { }

    public static void main(String[] args) {
        equal("{1,2}", "{2,1}");
        equal("{(1,2),(3,4)}", "{(3,4),(1,2)}");
        different("(1,2)", "(2,1)");
        different("[1,2]", "[2,1]");
        different("1", "TRUE");
        System.out.println("PASS: canonical B values (set order, tuples, sequences, primitive tags)");
    }

    private static void equal(String a, String b) {
        check(CanonicalBValue.encode(a).equals(CanonicalBValue.encode(b)),
                "Semantically equal expressions have different encodings: " + a + " / " + b);
    }

    private static void different(String a, String b) {
        check(!CanonicalBValue.encode(a).equals(CanonicalBValue.encode(b)),
                "Distinct expressions have identical encodings: " + a + " / " + b);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
