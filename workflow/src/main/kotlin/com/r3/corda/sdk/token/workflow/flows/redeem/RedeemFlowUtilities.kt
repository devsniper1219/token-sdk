package com.r3.corda.sdk.token.workflow.flows.redeem

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.sdk.token.contracts.commands.RedeemTokenCommand
import com.r3.corda.sdk.token.contracts.states.AbstractToken
import com.r3.corda.sdk.token.contracts.states.FungibleToken
import com.r3.corda.sdk.token.contracts.types.TokenType
import com.r3.corda.sdk.token.contracts.utilities.sumTokenStateAndRefs
import com.r3.corda.sdk.token.workflow.flows.internal.checkSameIssuer
import com.r3.corda.sdk.token.workflow.flows.internal.checkSameNotary
import com.r3.corda.sdk.token.workflow.flows.internal.selection.TokenSelection
import com.r3.corda.sdk.token.workflow.flows.internal.selection.generateExitNonFungible
import com.r3.corda.sdk.token.workflow.types.PartyAndAmount
import com.r3.corda.sdk.token.workflow.types.PartyAndToken
import com.r3.corda.sdk.token.workflow.utilities.addNotaryWithCheck
import com.r3.corda.sdk.token.workflow.utilities.ownedTokensByTokenIssuer
import com.r3.corda.sdk.token.workflow.utilities.tokenAmountWithIssuerCriteria
import net.corda.core.contracts.Amount
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.node.ServiceHub
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.TransactionBuilder

/**
 * Add redeeming of multiple [inputs] to the [transactionBuilder] with possible [changeOutput].
 */
@Suspendable
@JvmOverloads
fun <T : TokenType> addRedeemTokens(
        transactionBuilder: TransactionBuilder,
        inputs: List<StateAndRef<AbstractToken<T>>>,
        changeOutput: AbstractToken<T>? = null
): TransactionBuilder {
    checkSameIssuer(inputs, changeOutput?.issuer)
    checkSameNotary(inputs)
    if (changeOutput != null && changeOutput is FungibleToken<T>) {
        check(inputs.filterIsInstance<StateAndRef<FungibleToken<T>>>().sumTokenStateAndRefs() > changeOutput.amount) {
            "Change output should be less than sum of inputs."
        }
    }
    val firstState = inputs.first().state
    addNotaryWithCheck(transactionBuilder, firstState.notary)
    val moveKey = firstState.data.holder.owningKey
    val issuerKey = firstState.data.issuer.owningKey
    val redeemCommand = RedeemTokenCommand(firstState.data.issuedTokenType)
    transactionBuilder.apply {
        inputs.forEach { addInputState(it) }
        if (changeOutput != null) addOutputState(changeOutput)
        addCommand(redeemCommand, issuerKey, moveKey)
    }
    return transactionBuilder
}

/**
 * Adds a single token redeem to a [transactionBuilder] with possible change [changeOutput].
 */
@Suspendable
@JvmOverloads
fun <T : TokenType> addRedeemTokens(
        transactionBuilder: TransactionBuilder,
        input: StateAndRef<AbstractToken<T>>,
        changeOutput: AbstractToken<T>? = null
): TransactionBuilder {
    return addRedeemTokens(transactionBuilder = transactionBuilder, inputs = listOf(input), changeOutput = changeOutput)
}

/**
 * Redeem non-fungible [ownedToken] issued by the [issuer] and add it to the [transactionBuilder].
 */
@Suspendable
fun <T : TokenType> addRedeemTokens(
        transactionBuilder: TransactionBuilder,
        serviceHub: ServiceHub,
        ownedToken: T,
        issuer: Party
): TransactionBuilder {
    val ownedTokenStateAndRef = serviceHub.vaultService.ownedTokensByTokenIssuer(ownedToken, issuer).states
    check(ownedTokenStateAndRef.size == 1) {
        "Exactly one owned token of a particular type $ownedToken should be in the vault at any one time."
    }
    val nonFungibleState = ownedTokenStateAndRef.first()
    addNotaryWithCheck(transactionBuilder, nonFungibleState.state.notary)
    generateExitNonFungible(transactionBuilder, nonFungibleState)
    return transactionBuilder
}

/**
 * Redeem amount of certain type of the token issued by [issuer]. Pay possible change to the [changeOwner] - it can be confidential identity.
 * Additional query criteria can be provided using [additionalQueryCriteria].
 */
@Suspendable
@JvmOverloads
fun <T : TokenType> addRedeemTokens(
        transactionBuilder: TransactionBuilder,
        serviceHub: ServiceHub,
        amount: Amount<T>,
        issuer: Party,
        changeOwner: AbstractParty,
        additionalQueryCriteria: QueryCriteria? = null
): TransactionBuilder {
    val tokenSelection = TokenSelection(serviceHub)
    val baseCriteria = tokenAmountWithIssuerCriteria(amount.token, issuer)
    val queryCriteria = additionalQueryCriteria?.let { baseCriteria.and(it) } ?: baseCriteria
    val fungibleStates = tokenSelection.attemptSpend(amount, TransactionBuilder().lockId, queryCriteria) // TODO We shouldn't expose lockId in this function
    checkSameNotary(fungibleStates)
    check(fungibleStates.isNotEmpty()) {
        "Received empty list of states to redeem."
    }
    val notary = fungibleStates.first().state.notary
    addNotaryWithCheck(transactionBuilder, notary)
    // TODO unify it, probably need to move generate exit to redeem utils
    tokenSelection.generateExit(
            builder = transactionBuilder,
            exitStates = fungibleStates,
            amount = amount,
            changeOwner = changeOwner
    )
    return transactionBuilder
}

// Extensions on FlowLogic.

/**
 *  Redeem non-fungible [ownedToken] issued by the [issuer] and add it to the [transactionBuilder].
 */
@Suspendable
fun <T : TokenType> FlowLogic<*>.addRedeemTokens(
        transactionBuilder: TransactionBuilder,
        ownedToken: T,
        issuer: Party
): TransactionBuilder {
    return addRedeemTokens(transactionBuilder, serviceHub, ownedToken, issuer)
}

/**
 * Redeem amount of certain type of the token issued by [issuer]. Pay possible change to the [changeOwner] - it can be confidential identity.
 * Additional query criteria can be provided using [additionalQueryCriteria].
 */
@Suspendable
@JvmOverloads
fun <T : TokenType> FlowLogic<*>.addRedeemTokens(
        transactionBuilder: TransactionBuilder,
        amount: Amount<T>,
        issuer: Party,
        changeOwner: AbstractParty,
        additionalQueryCriteria: QueryCriteria? = null
): TransactionBuilder {
    return addRedeemTokens(transactionBuilder, serviceHub, amount, issuer, changeOwner, additionalQueryCriteria)
}
