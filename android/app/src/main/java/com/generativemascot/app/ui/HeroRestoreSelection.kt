package com.generativemascot.app.ui

import com.generativemascot.app.data.MascotDto

/** A phone-created accepted hero need not have a record on the team backend. */
internal fun restoredHomeHero(restored: MascotDto?, accepted: MascotDto?, localAccepted: MascotDto?,
    active: MascotDto?, localBundled: MascotDto?): MascotDto? = when {
    waitingForHero(restored) || restored?.status=="AWAITING_ACCEPTANCE" -> restored
    restored?.status=="READY" -> restored
    accepted?.status=="READY" -> accepted
    localAccepted!=null -> localAccepted
    active!=null -> active
    localBundled!=null -> localBundled
    else -> restored
}
