package io.motohub.android.ipc;

/** Notifies a bound client when Core's T-Box session becomes available or is lost. */
oneway interface ITBoxSessionListener {
    void onSessionReady();
    void onSessionLost();

    /**
     * Something the transport wants the rider told while a session is being established or run,
     * in Core's own words. Not a failure: the session is still going.
     *
     * The one that made this necessary comes 30s into a ThinkerRide mirror-start, where the dash
     * has not dialled the phone's video port and the remaining wait is on the rider, who has to
     * long-press UP on the dash. Core's own modes have logged that line since 1.1.93; a companion
     * app driving a Core-owned session had no channel for it at all and left the rider looking at
     * "preparing the connection" for the full 75s window with nothing to act on.
     *
     * COMPATIBILITY RUNS THE OTHER WAY HERE. Every other tail-position call in this package is
     * implemented by Core and called by the companion, so the companion gates on
     * getContractVersion(). This one is implemented by the COMPANION and called by Core, and Core
     * cannot ask a listener what version it speaks. Appending it is safe anyway because the
     * interface is `oneway`: calling it on an older companion's stub is a transaction that
     * returns false and is never waited on. Core wraps the call regardless.
     *
     * @param message a complete sentence, already fit to show a rider.
     */
    void onTransportNotice(String message);
}
