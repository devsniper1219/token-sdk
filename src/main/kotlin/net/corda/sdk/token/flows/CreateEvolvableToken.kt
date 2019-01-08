package net.corda.sdk.token.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.ContractClassName
import net.corda.core.contracts.TransactionState
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.sdk.token.commands.TokenCommand
import net.corda.sdk.token.types.EvolvableToken
import net.corda.sdk.token.utilities.withNotary

/**
 * Flow for creating an evolvable token type. This is just a simple flow for now. Although it can be invoked via the
 * shell, it is more likely to be used for unit testing or called as an inlined-subflow.
 */
@StartableByRPC
class CreateEvolvableToken<T : EvolvableToken>(val transactionState: TransactionState<T>) : FlowLogic<SignedTransaction>() {

    constructor(evolvableToken: T, contract: ContractClassName, notary: Party) : this(TransactionState(evolvableToken, contract, notary))

    constructor(evolvableToken: T, notary: Party) : this(evolvableToken withNotary notary)

    @Suspendable
    override fun call(): SignedTransaction {
        // Create a transaction which updates the ledger with the new evolvable token.
        // Note that initally it is not shared with anyone.
        val evolvableToken = transactionState.data
        val signingKeys = evolvableToken.maintainers.map { it.owningKey }
        val utx: TransactionBuilder = TransactionBuilder().apply {
            addCommand(data = TokenCommand.Create(), keys = signingKeys)
            addOutputState(state = transactionState)
        }
        // Sign the transaction. Only Concrete Parties should be used here.
        val stx: SignedTransaction = serviceHub.signInitialTransaction(utx)
        // No need to pass in a session as there's no counterparty involved.
        return subFlow(FinalityFlow(transaction = stx, sessions = listOf()))
    }
}