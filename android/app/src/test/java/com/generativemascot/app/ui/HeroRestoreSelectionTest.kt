package com.generativemascot.app.ui

import com.generativemascot.app.data.MascotDto
import org.junit.Assert.*
import org.junit.Test

class HeroRestoreSelectionTest {
    private fun hero(id:String,status:String="READY")=MascotDto(id=id,status=status)
    @Test fun acceptedPhoneHeroSurvivesAnUnrelatedServerActiveHero() {
        val local=hero("phone-generated");val remote=hero("server-active")
        assertSame(local,restoredHomeHero(null,null,local,remote,hero("bundled")))
        assertSame(remote,restoredHomeHero(null,null,null,remote,hero("bundled")))
    }
    @Test fun unfinishedPaidWorkAndServerAcceptedHeroKeepTheirPriority() {
        val accepted=hero("accepted");val local=hero("local")
        for(status in listOf("BASE_GENERATING","AWAITING_ACCEPTANCE")) {
            val pending=hero("paid-job",status)
            assertSame(pending,restoredHomeHero(pending,accepted,local,hero("server"),null))
        }
        assertSame(accepted,restoredHomeHero(null,accepted,local,hero("server"),null))
    }
}
