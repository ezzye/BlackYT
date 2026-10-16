// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import java.util.List;
import java.io.IOException;
import okhttp3.Request;
import okhttp3.Response;

/** Small live-source preflight, so UI tests use a currently available public stream. */
final class DeviceStreamChecks {
    static YouTubeRepository.Track available(List<YouTubeRepository.Track> candidates,
            YouTubeRepository.Rules rules) throws Exception {
        YouTubeRepository repository=YouTubeRepository.get();int unavailable=0;
        for(YouTubeRepository.Track track:candidates.subList(0,Math.min(candidates.size(),6))) {
            try {
                YouTubeRepository.Resolved source=repository.resolve(track.id,rules,true);
                try(Response response=repository.mediaClient().newCall(new Request.Builder().url(source.url)
                        .header("User-Agent",YouTubeRepository.USER_AGENT).header("Range","bytes=0-63").build()).execute()) {
                    if(response.isSuccessful()&&response.body()!=null&&response.body().source().request(1)) {
                        Diagnostics.event("test-source-preflight","unavailableCandidates="+unavailable);return track;
                    }
                }
            }catch(Exception unavailableSource){/* No signed stream URL or exception text is logged. */}
            unavailable++;
        }
        throw new IOException("No available public stream among the first six search candidates.");
    }
}
