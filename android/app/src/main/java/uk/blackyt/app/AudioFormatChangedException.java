// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import java.io.IOException;

/** Stops a retry from resuming old decoder state with bytes from a different audio format. */
final class AudioFormatChangedException extends IOException {
    AudioFormatChangedException() {
        super("YT changed the selected audio format. Retry the current song to start it cleanly.");
    }
}
