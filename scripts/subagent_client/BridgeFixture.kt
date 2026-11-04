package dev.aster.ide.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.Socket

/** Loopback transport for the production coordinator and production connection codec. */
class BridgeClient(private val port: Int) {
    fun options() = JSONObject()
    suspend fun open(op: String, workspace: String?, extra: JSONObject, timeoutMillis: Int): BridgeConnection = withContext(Dispatchers.IO) {
        val socket = Socket("127.0.0.1", port)
        socket.soTimeout = timeoutMillis
        BridgeConnection(socket).also {
            extra.put("op", op).put("workspace", workspace)
            it.send(extra)
        }
    }
    suspend fun rpc(op: String, workspace: String?, extra: JSONObject, timeoutMillis: Int = 10000): JSONObject =
        open(op, workspace, extra, timeoutMillis).use {
            val response = it.receive()
            check(response.optBoolean("ok")) { response.optString("error") }
            response
        }
}