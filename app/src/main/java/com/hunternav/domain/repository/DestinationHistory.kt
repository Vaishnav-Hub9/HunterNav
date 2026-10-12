package com.hunternav.domain.repository

import com.hunternav.domain.model.Destination

/**
 * Records the destinations the user actually chose (search result taps or map pins) so the
 * home screen can offer them again as "Recent destinations".
 *
 * Contract: every entry is a real user selection with its EXACT selected coordinate — never a
 * centroid, a guess or a built-in placeholder. Implementations must be main-thread safe and
 * must never throw (a broken store degrades to an empty list, it must not break navigation).
 */
interface DestinationHistory {

    /** Moves [destination] to the front of the history, deduplicated by exact coordinate. */
    fun record(destination: Destination)

    /** Most-recent-first list of previously selected destinations. */
    fun recent(): List<Destination>
}
