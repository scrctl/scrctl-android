package org.scrctl.android

// Android pointer IDs are stable within a gesture, but need not fit the five XDB slots.
internal class TouchContacts(private val send: (Int, Point, Boolean, Boolean) -> Unit) {
    private data class Contact(val slot: Int, var point: Point)
    private val active = linkedMapOf<Int, Contact>()
    fun down(pointer: Int, point: Point) {
        if (pointer in active) return
        val slot = (0 until 5).firstOrNull { id -> active.values.none { it.slot == id } } ?: return
        active[pointer] = Contact(slot, point)
        send(slot, point, true, false)
    }
    fun move(pointer: Int, point: Point) {
        val contact = active[pointer] ?: return
        if (contact.point == point) return
        contact.point = point
        send(contact.slot, point, true, true)
    }
    fun up(pointer: Int, point: Point?) {
        val contact = active.remove(pointer) ?: return
        send(contact.slot, point ?: contact.point, false, false)
    }
    fun release() {
        val held = active.values.toList()
        active.clear()
        held.forEach { send(it.slot, it.point, false, false) }
    }
}
