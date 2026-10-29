package com.unbihex.vpn

/**
 * The app's Rust side, for connections made without the UI (quick settings tile,
 * always-on VPN). It shares one client with the UI, so both see the same state.
 *
 * Every call blocks: keep them off the main thread.
 */
internal object EngineBridge {
    init {
        // The app's native library: the JNI functions live there, next to the client.
        System.loadLibrary("unbihex_client_lib")
    }

    /** Interface parameters of the selected server as `StartArgs` JSON; throws when it cannot connect. */
    @JvmStatic
    external fun tunArgs(dataDir: String): String

    /** Starts the engine on the TUN descriptor; throws on failure. */
    @JvmStatic
    external fun connect(fd: Int)

    /** Stops the engine. Idempotent. */
    @JvmStatic
    external fun disconnect()

    /** Bytes `[up, down]` of the running engine, null when nothing runs. */
    @JvmStatic
    external fun stats(): LongArray?
}
