package org.scrctl.android

import org.junit.Assert.*
import org.junit.Test

class TouchContactsTest {
    @Test fun stableSlotsSurvivePointerReorderingAndIndividualRelease() {
        data class Event(val slot: Int, val point: Point, val down: Boolean, val move: Boolean)
        val events = mutableListOf<Event>()
        val contacts = TouchContacts { slot, point, down, move -> events.add(Event(slot, point, down, move)) }
        val ids = listOf(27, 8, 42, 3, 19)
        ids.forEachIndexed { i, id -> contacts.down(id, Point(i / 5f, .2f)) }
        contacts.down(99, Point(.5f, .5f)) // A sixth pointer is ignored for its entire lifetime.
        contacts.move(99, Point(.6f, .6f))
        assertEquals(listOf(0, 1, 2, 3, 4), events.map { it.slot })
        ids.reversed().forEach { contacts.move(it, Point(.8f, .9f)) }
        assertEquals(listOf(4, 3, 2, 1, 0), events.takeLast(5).map { it.slot })
        contacts.up(42, Point(.7f, .8f))
        assertEquals(Event(2, Point(.7f, .8f), false, false), events.last())
        contacts.move(8, Point(.3f, .4f))
        assertEquals(1, events.last().slot)
        contacts.down(100, Point(.1f, .2f))
        assertEquals(2, events.last().slot)
        contacts.up(99, null)
        contacts.release()
        assertEquals(setOf(0, 1, 2, 3, 4), events.takeLast(5).map { it.slot }.toSet())
        assertTrue(events.takeLast(5).all { !it.down })
        val size = events.size
        contacts.release(); contacts.move(27, Point(.5f, .5f))
        assertEquals(size, events.size)
    }
}
