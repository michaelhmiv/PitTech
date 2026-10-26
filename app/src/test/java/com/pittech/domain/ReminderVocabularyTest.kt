package com.pittech.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** PL-01/PL-02 (issue #172): the announced-action rule and the one snooze. */
class ReminderVocabularyTest {
    @Test fun milestoneRuleAnnouncesItsMilestoneAction() {
        val trigger = ReminderTrigger.Historical(anchorAction = "food_on", offsetMillis = 10_800_000)
        assertEquals("wrap", ReminderVocabulary.announcedAction(trigger, "wrap"))
        assertEquals("remove", ReminderVocabulary.announcedAction(trigger, "remove"))
    }

    @Test fun milestoneRuleWithoutResolvableMilestoneAnnouncesNothing() {
        val trigger = ReminderTrigger.Historical(anchorAction = "food_on", offsetMillis = 0)
        assertNull(ReminderVocabulary.announcedAction(trigger, null))
        assertNull(ReminderVocabulary.announcedAction(trigger, "not_an_action"))
        assertNull(ReminderVocabulary.announcedAction(trigger, ""))
    }

    @Test fun otherTriggersNeverAnnounceAnAction() {
        // Even if a milestone action is supplied, only historical rules use it;
        // an Action trigger's action is the anchor that already happened, not
        // an action the prompt announced.
        assertNull(ReminderVocabulary.announcedAction(ReminderTrigger.Duration(1_000, 60_000), "wrap"))
        assertNull(ReminderVocabulary.announcedAction(ReminderTrigger.Clock(1_000), "wrap"))
        assertNull(ReminderVocabulary.announcedAction(ReminderTrigger.Action("food_on", 0), "wrap"))
        assertNull(ReminderVocabulary.announcedAction(
            ReminderTrigger.Probe("series", null, "food", "above", 165.0), "wrap"))
    }

    @Test fun snoozeIsTenMinutesEverywhere() {
        assertEquals(600_000L, ReminderVocabulary.SNOOZE_MILLIS)
    }
}
