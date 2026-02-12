package io.novafoundation.nova.feature_bridge_api.presentation

interface BridgeRouter {

    fun openBridgeDeposit()

    fun openBridgeWithdraw()

    fun openBridgeStatus()

    fun back()
}
