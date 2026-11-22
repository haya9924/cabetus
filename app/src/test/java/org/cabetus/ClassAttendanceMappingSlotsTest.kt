package org.cabetus
import org.cabetus.data.classweb.ClassAttendanceMappingSlots
import org.junit.Assert.*
import org.junit.Test
class ClassAttendanceMappingSlotsTest {
    @Test fun weeklyTwoSlotsCanOfferExplicitMappingForExistingOne() {
        assertTrue(ClassAttendanceMappingSlots.matches(setOf(2 to 4), setOf(2 to 4, 5 to 1)))
        assertTrue(ClassAttendanceMappingSlots.matches(setOf(3 to 1), setOf(3 to 1, 5 to 1)))
    }
    @Test fun conflictingSlotCannotBeOffered() {
        assertFalse(ClassAttendanceMappingSlots.matches(setOf(2 to 3), setOf(2 to 4, 5 to 1)))
        assertFalse(ClassAttendanceMappingSlots.matches(emptySet(), setOf(2 to 4)))
    }
}
