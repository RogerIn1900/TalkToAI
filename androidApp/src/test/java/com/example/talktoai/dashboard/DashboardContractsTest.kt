package com.example.talktoai.dashboard

import com.talktoai.marketui.dashboard.*
import org.junit.Assert.*
import org.junit.Test

class DashboardContractsTest {
    @Test
    fun csvRejectsQuotesInsidePlainFieldsAndCharactersAfterClosingQuote() {
        listOf("label,value\na\"b\",1", "label,value\n\"a\"tail,1", "label,value\na,1\"\"")
            .forEach {
                assertThrows(IllegalArgumentException::class.java) { DashboardCsv.parse(it) }
            }
    }

    @Test
    fun csvAcceptsEscapedQuotesMultilineFieldsBomAndExactRowLimit() {
        assertEquals(
            listOf(Datum("a\"b\nc", 1.0)),
            DashboardCsv.parse("\uFEFFlabel,value\r\n\"a\"\"b\nc\",1\r\n"),
        )
        assertEquals(
            DashboardCsv.MAX_ROWS,
            DashboardCsv.parse("label,value\n" + "a,1\n".repeat(DashboardCsv.MAX_ROWS)).size,
        )
        assertThrows(IllegalArgumentException::class.java) {
            DashboardCsv.parse("label,value\n" + "a".repeat(DashboardCsv.MAX_BYTES) + ",1")
        }
    }

    @Test
    fun documentRoundTripPreservesConfigurationAndLayout() {
        val doc =
            DashboardDocument(
                "测试",
                listOf(
                    DashboardCard(
                        "id",
                        "标题",
                        "source",
                        ChartKind.LINE,
                        rect = GridRect(2, 3, 4, 13),
                        autoHeight = false,
                        filter = ValueRange(-1.0, 10.0),
                        axis = ValueRange(0.0, 5.0),
                        time = TimeRange(10, 20),
                    )
                ),
            )
        assertEquals(doc, DashboardJson.decode(DashboardJson.encode(doc)))
    }

    @Test
    fun unknownSchemaIsRejected() {
        val json =
            DashboardJson.encode(DashboardDocument("a", emptyList()))
                .replace("\"version\":1", "\"version\":9")
        try {
            DashboardJson.decode(json)
            fail("Unknown version accepted")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun csvSupportsQuotesCrLfAndOptionalTimestamp() {
        val rows = DashboardCsv.parse("label,value,timeMs\r\n\"A,B\",12.5,100\r\nC,-2,\r\n")
        assertEquals(listOf(Datum("A,B", 12.5, 100), Datum("C", -2.0)), rows)
    }

    @Test
    fun csvRejectsInvalidAndOversizedInputs() {
        listOf(
                "label,value\na,NaN",
                "label,value\na,Infinity",
                "a,b\nc,1",
                "label,value\n\"broken,2",
                "label,value\na,2,extra",
                "label,value\n" + "a,1\n".repeat(DashboardCsv.MAX_ROWS + 1),
            )
            .forEach { value ->
                try {
                    DashboardCsv.parse(value)
                    fail("Invalid CSV accepted")
                } catch (_: IllegalArgumentException) {}
            }
    }
}
