package com.einsteinmods.opendoors.core.net.portmap;

import java.net.InetSocketAddress;

/**
 * A port the router forwards to the game's UDP socket.
 *
 * @param method "PCP", "NAT-PMP" or "UPnP"
 * @param external the router's public address and the mapped port
 * @param lifetimeSeconds lease granted (0 = permanent)
 */
public record PortMapping(String method, InetSocketAddress external, int lifetimeSeconds) {}
