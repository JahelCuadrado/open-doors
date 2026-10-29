package com.einsteinmods.opendoors.core.net;

import java.io.IOException;
import java.nio.channels.SelectionKey;

/** A channel registered on the network thread's selector besides the UDP socket. */
interface SelectableHandler {
  void onReady(SelectionKey key) throws IOException;
}
