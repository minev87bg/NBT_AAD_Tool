package com.nbtaad.editor.addon

import com.nbtaad.editor.experimental.Json
import com.nbtaad.editor.experimental.asList
import com.nbtaad.editor.experimental.asObj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Reading the drawing the AI writes, and the request that asks for it. */
class IconSpecTest {
    private val good = """
        Here is the icon:
        ```json
        {"shapes":[
          {"type":"round","x":0,"y":0,"w":100,"h":100,"r":22,"fill":"#1A73E8"},
          {"type":"circle","cx":50,"cy":50,"r":30,"stroke":"#FFFFFF","width":6},
          {"type":"poly","points":[[40,32],[40,68],[70,50]],"fill":"#fff"},
          {"type":"line","x1":10,"y1":90,"x2":90,"y2":90,"fill":"#ffffff80","width":3},
          {"type":"arc","cx":50,"cy":50,"r":40,"from":200,"to":340,"stroke":"#ff0000"}
        ]}
        ```
        Hope it helps.
    """.trimIndent()

    @Test
    fun aDrawingInsideTextAndACodeBlockIsFound() {
        val s = IconSpec.parse(good)
        assertEquals(5, s.shapes.size)
        assertEquals(0, s.skipped)
        assertEquals(listOf("round", "circle", "poly", "line", "arc"), s.shapes.map { it.type })
        assertEquals(0xFF1A73E8.toInt(), s.shapes[0].fill)
        assertNull(s.shapes[1].fill)
        assertEquals(0xFFFFFFFF.toInt(), s.shapes[1].stroke)
        assertEquals(6f, s.shapes[1].width)
        assertEquals(3, s.shapes[2].points.size)
    }

    @Test
    fun aLineAndAnArcTakeTheirColourFromFillWhenThereIsNoStroke() {
        val line = IconSpec.parse(good).shapes[3]
        assertEquals(0x80FFFFFF.toInt(), line.stroke)
        assertNull(line.fill)
    }

    @Test
    fun colours() {
        assertEquals(0xFFFFFFFF.toInt(), IconSpec.color("#fff"))
        assertEquals(0xFF112233.toInt(), IconSpec.color("#112233"))
        assertEquals(0x44112233, IconSpec.color("#11223344"))
        assertEquals(0xFF2200FF.toInt(), IconSpec.color(" #20f "))
        assertNull(IconSpec.color("red"))
        assertNull(IconSpec.color("#12"))
        assertNull(IconSpec.color("#12345g"))
    }

    @Test
    fun whatCannotBeDrawnIsLeftOutAndCounted() {
        val s = IconSpec.parse(
            """{"shapes":[
                {"type":"circle","cx":50,"cy":50,"r":20,"fill":"#00ff00"},
                {"type":"text","x":1,"y":2,"fill":"#000000"},
                {"type":"circle","cx":50,"r":20,"fill":"#00ff00"},
                {"type":"circle","cx":50,"cy":50,"r":20},
                {"type":"circle","cx":50,"cy":50,"r":20,"fill":"green"},
                {"type":"poly","points":[[1,1]],"fill":"#000"},
                "nonsense"
            ]}""",
        )
        assertEquals(1, s.shapes.size)
        assertEquals(6, s.skipped)
    }

    @Test
    fun numbersAreHeldInsideTheSheetsReach() {
        val s = IconSpec.parse("""{"shapes":[{"type":"circle","cx":9999,"cy":-9999,"r":9999,"fill":"#000"}]}""").shapes[0]
        assertEquals(150f, s.n["cx"]); assertEquals(-50f, s.n["cy"]); assertEquals(200f, s.n["r"])
        val w = IconSpec.parse("""{"shapes":[{"type":"line","x1":0,"y1":0,"x2":1,"y2":1,"stroke":"#000","width":9999}]}""").shapes[0]
        assertEquals(60f, w.width)
    }

    @Test
    fun theNumberOfShapesIsLimited() {
        val many = (1..120).joinToString(",") { """{"type":"circle","cx":50,"cy":50,"r":$it,"fill":"#000"}""" }
        val s = IconSpec.parse("""{"shapes":[$many]}""")
        assertEquals(IconSpec.MAX_SHAPES, s.shapes.size)
        assertEquals(40, s.skipped)
    }

    @Test
    fun anAnswerWithoutADrawingIsRefusedInPlainWords() {
        for (bad in listOf("I cannot draw that.", "{\"shapes\": 3}", "{\"shapes\":[]}", "{\"shapes\":[{\"type\":\"star\"}]}", "{not json}")) {
            try {
                IconSpec.parse(bad)
                fail("accepted: $bad")
            } catch (e: IconSpec.Bad) {
                assertTrue(e.message!!.endsWith("."))
            }
        }
    }

    @Test
    fun theRequestIsValidJsonAndKeepsTheDescriptionAsData() {
        val tricky = "a \"quoted\" icon\nwith a new line and a back\\slash"
        val req = Json.parse(IconSpec.requestJson(tricky)).asObj()
        assertNotNull(req)
        req!!
        assertEquals(IconSpec.MAX_TOKENS, (req["max_tokens"] as Number).toInt())
        val content = (req["messages"].asList()!![0].asObj()!!["content"]) as String
        assertTrue(content.startsWith("<description>"))
        assertTrue(content.contains(tricky))
        val system = req["system"] as String
        assertTrue(system.contains("ORIGINAL"))
        assertTrue(system.contains("Do not copy the logo"))
    }

    @Test
    fun theDescriptionIsChecked() {
        assertNotNull(IconSpec.descriptionProblem("   "))
        assertNotNull(IconSpec.descriptionProblem("x".repeat(301)))
        assertNull(IconSpec.descriptionProblem("a white steering wheel on a blue rounded square"))
    }
}
