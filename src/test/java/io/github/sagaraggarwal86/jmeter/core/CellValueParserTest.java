package io.github.sagaraggarwal86.jmeter.core;

import io.github.sagaraggarwal86.jmeter.listener.core.CellValueParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CellValueParser}.
 *
 * <p>All methods return {@code 0.0} on null, blank, or unparseable input — no
 * exceptions are ever thrown. These tests verify that contract plus edge cases
 * around stripping suffixes and thousands separators.</p>
 */
@DisplayName("CellValueParser")
class CellValueParserTest {

    // ─────────────────────────────────────────────────────────────
    // parseErrorRate(String)
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("parseErrorRate(String)")
    class ParseErrorRateStringTests {

        @Test
        @DisplayName("null returns 0.0")
        void nullReturnsZero() {
            assertEquals(0.0, CellValueParser.parseErrorRate((String) null));
        }

        @Test
        @DisplayName("blank string returns 0.0")
        void blankReturnsZero() {
            assertEquals(0.0, CellValueParser.parseErrorRate("   "));
        }

        @Test
        @DisplayName("'1.23%' returns 1.23")
        void percentageSuffix() {
            assertEquals(1.23, CellValueParser.parseErrorRate("1.23%"), 0.001);
        }

        @Test
        @DisplayName("'50' without % returns 50.0")
        void noSuffix() {
            assertEquals(50.0, CellValueParser.parseErrorRate("50"), 0.001);
        }

        @Test
        @DisplayName("unparseable returns 0.0")
        void unparseableReturnsZero() {
            assertEquals(0.0, CellValueParser.parseErrorRate("abc%"));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // parseErrorRate(Object)
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("parseErrorRate(Object)")
    class ParseErrorRateObjectTests {

        @Test
        @DisplayName("null Object returns 0.0")
        void nullObjectReturnsZero() {
            assertEquals(0.0, CellValueParser.parseErrorRate((Object) null));
        }

        @Test
        @DisplayName("Object with toString '2.50%' returns 2.50")
        void objectToString() {
            assertEquals(2.50, CellValueParser.parseErrorRate((Object) "2.50%"), 0.001);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // parseDouble(String)
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("parseDouble(String)")
    class ParseDoubleStringTests {

        @Test
        @DisplayName("null returns 0.0")
        void nullReturnsZero() {
            assertEquals(0.0, CellValueParser.parseDouble((String) null));
        }

        @Test
        @DisplayName("blank returns 0.0")
        void blankReturnsZero() {
            assertEquals(0.0, CellValueParser.parseDouble(""));
        }

        @Test
        @DisplayName("'3.14' returns 3.14")
        void validDouble() {
            assertEquals(3.14, CellValueParser.parseDouble("3.14"), 0.001);
        }

        @Test
        @DisplayName("'  42  ' with whitespace returns 42.0")
        void whitespace() {
            assertEquals(42.0, CellValueParser.parseDouble("  42  "), 0.001);
        }

        @Test
        @DisplayName("unparseable returns 0.0")
        void unparseableReturnsZero() {
            assertEquals(0.0, CellValueParser.parseDouble("not-a-number"));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // parseDouble(Object)
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("parseDouble(Object)")
    class ParseDoubleObjectTests {

        @Test
        @DisplayName("null Object returns 0.0")
        void nullObjectReturnsZero() {
            assertEquals(0.0, CellValueParser.parseDouble((Object) null));
        }

        @Test
        @DisplayName("Integer object '100' returns 100.0")
        void integerObject() {
            assertEquals(100.0, CellValueParser.parseDouble((Object) "100"), 0.001);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // parseMs(String)
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("parseMs(String)")
    class ParseMsTests {

        @Test
        @DisplayName("null returns 0.0")
        void nullReturnsZero() {
            assertEquals(0.0, CellValueParser.parseMs(null));
        }

        @Test
        @DisplayName("blank returns 0.0")
        void blankReturnsZero() {
            assertEquals(0.0, CellValueParser.parseMs("  "));
        }

        @Test
        @DisplayName("'312' returns 312.0")
        void simpleMs() {
            assertEquals(312.0, CellValueParser.parseMs("312"), 0.001);
        }

        @Test
        @DisplayName("'1,024' with comma separator returns 1024.0")
        void thousandsSeparator() {
            assertEquals(1024.0, CellValueParser.parseMs("1,024"), 0.001);
        }

        @Test
        @DisplayName("'1,000,000' with multiple commas returns 1000000.0")
        void multipleCommas() {
            assertEquals(1_000_000.0, CellValueParser.parseMs("1,000,000"), 0.001);
        }

        @Test
        @DisplayName("unparseable returns 0.0")
        void unparseableReturnsZero() {
            assertEquals(0.0, CellValueParser.parseMs("N/A"));
        }
    }
}
