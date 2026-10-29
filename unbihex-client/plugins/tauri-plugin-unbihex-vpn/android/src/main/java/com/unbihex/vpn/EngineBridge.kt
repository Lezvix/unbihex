package com.unbihex.vpn

/** The app's Rust side; it shares one client with the UI. Every call blocks. */
internal object EngineBridge {
    init {
        System.loadLibrary("unbihex_client_lib")
    }

    /** `StartArgs` JSON of the selected server. */
    @JvmStatic
    external fun tunArgs(dataDir: String): String

    /** Starts the engine with the plan of the last [tunArgs]. */
    @JvmStatic
    external fun connect(fd: Int)

    @JvmStatic
    external fun disconnect()

    /** `[up, down]` bytes. */
    @JvmStatic
    external fun stats(): LongArray?

    @JvmStatic
    external fun session(): String?
}
