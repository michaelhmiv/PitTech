package com.pittech.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A library full of same-named recipes is unusable, so a new recipe whose
 * name is already taken is prefilled with a distinguishable variant the
 * user can edit before saving — never blocked, never silently identical.
 * The variant carries the date it was added ("Boston Butt · Oct 27"),
 * matching the visible added-date in the library; a same-day repeat gets
 * a counter ("Boston Butt · Oct 27 (2)").
 */
internal fun differentiatedRecipeName(name: String, takenNames: Collection<String>, today: LocalDate = LocalDate.now()): String {
    val taken = takenNames.map { it.trim().lowercase() }.toSet()
    if (name.trim().lowercase() !in taken) return name
    val dated = name + " · " + today.format(DateTimeFormatter.ofPattern("MMM d", Locale.US))
    if (dated.trim().lowercase() !in taken) return dated
    var counter = 2
    while ("$dated ($counter)".trim().lowercase() in taken) counter++
    return "$dated ($counter)"
}
