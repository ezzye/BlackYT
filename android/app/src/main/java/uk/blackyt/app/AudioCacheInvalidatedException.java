// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

/** Reopen at the same byte/position after expiry or an explicit cache clear. */
final class AudioCacheInvalidatedException extends java.io.IOException {
    AudioCacheInvalidatedException() { super("Cached audio expired or was cleared."); }
}
