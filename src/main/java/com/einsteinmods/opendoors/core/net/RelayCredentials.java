package com.einsteinmods.opendoors.core.net;

import java.net.InetSocketAddress;

/** A resolved TURN server and the short-lived credentials the hub minted for it. */
public record RelayCredentials(InetSocketAddress server, String username, String password) {}
