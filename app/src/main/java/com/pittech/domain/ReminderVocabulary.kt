package com.pittech.domain

/**
 * Issue #172, PL-01 + PL-02: one reminder vocabulary across the legacy timer,
 * the unified reminder rules and playbook guidance — **Log it** records what
 * happened, **Snooze 10 min** asks again in ten minutes, **Dismiss** ends the
 * reminder without recording anything. The words mean the same thing on every
 * prompt, in the app and in notifications.
 */
object ReminderVocabulary {
    /** The one snooze length, everywhere. The legacy check-in's hidden
     * one-hour snooze (triggered silently by the scrim or Back) is gone:
     * closing a check-in changes nothing. */
    const val SNOOZE_MILLIS: Long = 600_000

    const val LOG_IT = "Log it"
    const val SNOOZE = "Snooze 10 min"
    const val DISMISS = "Dismiss"

    /**
     * The physical action a fired reminder announced, when it announced one.
     *
     * Only a previous-cook milestone rule names an action: the milestone it
     * was created from. [milestoneAction] is that milestone's recorded action,
     * resolved from the source recipe snapshot by the caller. Every other
     * trigger returns null — the caller must then ask explicitly and never
     * preselect an action. (The record-an-action dialog used to default to
     * "wrap" for every rule; the #172 audit flagged that blanket default as a
     * data-integrity hazard.)
     */
    fun announcedAction(trigger: ReminderTrigger, milestoneAction: String?): String? = when (trigger) {
        is ReminderTrigger.Historical -> milestoneAction?.takeIf { it in CookPhases.phaseActions }
        else -> null
    }
}
