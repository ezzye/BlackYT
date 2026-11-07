// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import java.math.BigInteger;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Stable byte identity only; signed URL material must never be retained in the cache descriptor. */
final class AudioCacheFormatIdentity {
    private AudioCacheFormatIdentity() { }

    static String create(String url, int selectedItag, String trackId, String trackType,
            String mime, String codec, int bitrate) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getRawFragment() != null
                    || !(host.equalsIgnoreCase("googlevideo.com")
                    || host.toLowerCase(Locale.ROOT).endsWith(".googlevideo.com"))) return "";
            String query = uri.getRawQuery();
            if (query == null) return "";
            String itag = null, length = null, modified = null, tags = null, urlTrack = null, urlId = null;
            for (String part : query.split("&", -1)) {
                int equals = part.indexOf('=');
                String name = URLDecoder.decode(equals < 0 ? part : part.substring(0, equals), "UTF-8");
                if (!name.equals("itag") && !name.equals("clen") && !name.equals("lmt")
                        && !name.equals("xtags") && !name.equals("audio_track_id") && !name.equals("id")) continue;
                String value = equals < 0 ? "" : URLDecoder.decode(part.substring(equals + 1), "UTF-8");
                if (name.equals("xtags") || name.equals("audio_track_id") || name.equals("id")) {
                    if (value.isEmpty() || value.length() > 1024) return "";
                    if (name.equals("xtags")) { if (tags != null) return ""; tags = value; }
                    else if (name.equals("audio_track_id")) { if (urlTrack != null) return ""; urlTrack = value; }
                    else { if (urlId != null) return ""; urlId = value; }
                    continue;
                }
                value = positiveNumber(value);
                if (value == null) return "";
                if (name.equals("itag")) { if (itag != null) return ""; itag = value; }
                else if (name.equals("clen")) { if (length != null) return ""; length = value; }
                else { if (modified != null) return ""; modified = value; }
            }
            if (itag == null || length == null || modified == null) return "";
            if (selectedItag > 0 && !itag.equals(Integer.toString(selectedItag))) return "";
            int safeBitrate = Math.max(0, bitrate);
            String material = field(itag) + field(length) + field(modified) + field(trackId)
                    + field(trackType) + field(tags) + field(urlTrack) + field(urlId) + field(mime == null ? "" : mime.toLowerCase(Locale.ROOT))
                    + field(codec) + field(Integer.toString(safeBitrate));
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("v3:");
            for (byte b : hash) result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return result.append(':').append(safeBitrate).toString();
        } catch (Exception invalid) {
            return "";
        }
    }
    private static String positiveNumber(String value) {
        if (value == null || !value.matches("[0-9]{1,20}")) return null;
        BigInteger number = new BigInteger(value);
        return number.signum() > 0 ? number.toString() : null;
    }
    private static String field(String value) {
        String safe = value == null ? "" : value;
        return safe.length() + ":" + safe;
    }
}
