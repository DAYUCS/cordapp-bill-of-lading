package com.example.flow

import co.paralleluniverse.fibers.Suspendable
import com.example.contract.BLContract
import com.example.state.BLState
import net.corda.core.contracts.Command
import net.corda.core.contracts.Requirements.using
import net.corda.core.contracts.StateAndContract
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

object ShippingFlow {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(val stateRef: StateRef): FlowLogic<SignedTransaction>() {
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GENERATING_TRANSACTION : ProgressTracker.Step("Obtaining bl from vault.")
            object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
            object COLLECTING : ProgressTracker.Step("Collecting counter party signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }
            object FINALISING : ProgressTracker.Step("Finalising transaction") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }
            fun tracker() = ProgressTracker(
                    GENERATING_TRANSACTION,
                    VERIFYING_TRANSACTION,
                    SIGNING_TRANSACTION,
                    COLLECTING,
                    FINALISING
            )
        }

        override val progressTracker = tracker()

        /**
         * The flow logic is encapsulated within the call() method.
         */
        @Suspendable
        override fun call(): SignedTransaction {
            // Obtain a reference to the notary we want to use.
            val notary = serviceHub.networkMapCache.notaryIdentities[0]

            // Stage 1.
            progressTracker.currentStep = GENERATING_TRANSACTION
            val blStateAndRefs = serviceHub.vaultService.queryBy<BLState>()
                    .states.associateBy( {it.ref}, {it} )

            val blStateAndRef = blStateAndRefs[stateRef] ?: throw IllegalArgumentException("BLState with StateRef $stateRef not found.")
            val inputBL = blStateAndRef.state.data

            require(serviceHub.myInfo.legalIdentities.first() == inputBL.owner) { "BL transfer can only be initiated by the BL owner." }

            val outputBL = inputBL.withNewOwner(inputBL.importerBank)

            // Generate an unsigned transaction.
            val txCommand = Command(BLContract.Commands.Move(), listOf(inputBL.shippingCompany.owningKey, inputBL.importerBank.owningKey))
            val txBuilder = TransactionBuilder(notary).withItems(inputBL, outputBL, txCommand)

            // Stage 2.
            progressTracker.currentStep = VERIFYING_TRANSACTION
            // Verify that the transaction is valid.
            txBuilder.verify(serviceHub)

            // Stage 3.
            progressTracker.currentStep = SIGNING_TRANSACTION
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 4. Collect signature from shipping company and importer bank and add it to the transaction.
            // This also verifies the transaction and checks the signatures.
            val otherPartyFlow = initiateFlow(inputBL.importerBank)
            progressTracker.currentStep = COLLECTING
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(otherPartyFlow), ExampleFlow.Initiator.Companion.GATHERING_SIGS.childProgressTracker()))

            // Stage 5. Notarise and record, the transaction in our vaults.
            progressTracker.currentStep = FINALISING
            return subFlow(FinalityFlow(fullySignedTx, ExampleFlow.Initiator.Companion.FINALISING_TRANSACTION.childProgressTracker()))
        }
    }

    @InitiatedBy(ShippingFlow.Initiator::class)
    class Responder(val otherPartyFlow: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(otherPartyFlow) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    // add check here
                }
            }

            return subFlow(signTransactionFlow)
        }
    }
}