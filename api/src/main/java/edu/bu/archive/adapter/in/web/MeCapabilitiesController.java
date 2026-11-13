package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.me.CapabilitiesResponse;
import edu.bu.archive.application.me.CapabilitiesService;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/me/capabilities} - what the signed-in caller may do.
 *
 * <p>Authentication comes from the existing chain: {@code SecurityConfiguration}
 * declares {@code /api/**} as {@code .authenticated()}, so an absent, invalid
 * or expired token is rejected with **401** before this controller runs. That
 * is the only error status this endpoint produces.
 *
 * <p>Every other outcome is **200**. "You are not an administrator" is a
 * normal answer, not an error - returning 403 would reintroduce exactly the
 * ambiguity this endpoint exists to remove.
 *
 * <p>{@code Cache-Control: no-store}: capability is security state, and a
 * stale {@code true} after a revoked membership is precisely the wrong thing
 * to serve from a cache.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeCapabilitiesController {

    private final CapabilitiesService capabilities;

    public MeCapabilitiesController(CapabilitiesService capabilities) {
        this.capabilities = capabilities;
    }

    @GetMapping("/capabilities")
    public ResponseEntity<CapabilitiesResponse> capabilities() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(capabilities.current());
    }
}
