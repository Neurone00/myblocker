package com.neurone.myblocker.proxy

/**
 * Live counters for the deep-clean interception path, so the app can show whether tidying is
 * actually happening. Updated from the proxy and the service; read by the UI. Reset when the
 * tunnel restarts.
 */
object DeepCleanStats {
    /** True while an intercepting proxy is running (deep clean on, cert installed, browser tidying on). */
    @Volatile var intercepting: Boolean = false

    /** Browser TLS connections handed to the proxy since the tunnel started. */
    @Volatile var connections: Long = 0

    /** HTML pages that had the tidy rules injected. */
    @Volatile var pagesTidied: Long = 0

    /** Connections tunnelled raw for certificate-pinned or excluded hosts. */
    @Volatile var passthroughs: Long = 0

    /** QUIC (UDP/443) datagrams dropped to force interceptable TCP. */
    @Volatile var quicDropped: Long = 0

    fun reset() {
        connections = 0
        pagesTidied = 0
        passthroughs = 0
        quicDropped = 0
    }
}
