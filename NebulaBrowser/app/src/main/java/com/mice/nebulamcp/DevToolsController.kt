package com.mice.nebulamcp

class DevToolsController(
    private val inspector: NetworkInspector
) {
    fun networkEntries(): List<NetworkEntry> = inspector.snapshot()

    fun clearNetwork() = inspector.clear()

    fun exportHar(): String = inspector.toHarJson()
}
