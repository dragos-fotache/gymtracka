package com.dragosfotache.gymtracka.data.sync.session

/** Safe preparation outcomes. Only Prepared carries authority for a later explicit confirmation. */
internal sealed interface OrdinaryRecoveryPreparation {
    class Prepared(val confirmation: AccountRecoveryConfirmation) : OrdinaryRecoveryPreparation {
        override fun toString(): String = "OrdinaryRecoveryPreparation.Prepared(redacted)"
    }

    data object NothingEligible : OrdinaryRecoveryPreparation
    data object Blocked : OrdinaryRecoveryPreparation
    data object Failed : OrdinaryRecoveryPreparation
}
