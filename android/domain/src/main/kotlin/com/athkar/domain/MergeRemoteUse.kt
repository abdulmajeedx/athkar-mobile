package com.athkar.domain

import com.athkar.core.domain.AdhkarReminder
import com.athkar.core.domain.EntityConflictResolver

/**
 * Application-level use case: reconcile an incoming server copy of an entity with the local copy
 * using per-field LWW conflict resolution (the core proves associativity/commutativity/idempotence).
 * The Data layer calls this before persisting a remote change, so the DB is the single source of
 * truth and never diverges from the converged merge.
 */
object MergeRemoteUse {
    fun reconcile(local: AdhkarReminder?, remote: AdhkarReminder): AdhkarReminder =
        local?.let { EntityConflictResolver.merge(it, remote) } ?: remote
}
