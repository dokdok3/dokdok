package com.dokdok.ranking;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class RankingController {

    private final RankingService rankingService;

    public RankingController(RankingService rankingService) {
        this.rankingService = rankingService;
    }

    @GetMapping("/drivers/{driverId}/activity-region")
    RankingService.ActivityRegion getActivityRegion(@PathVariable String driverId) {
        return rankingService.getActivityRegion(driverId);
    }

    @PutMapping("/drivers/{driverId}/activity-region")
    RankingService.ActivityRegion updateActivityRegion(
            @PathVariable String driverId,
            @RequestBody RankingService.ActivityRegionUpdateRequest request) {
        return rankingService.updateActivityRegion(driverId, request);
    }

    @GetMapping("/offers")
    RankingService.OfferPage getOffers(
            @RequestParam String driverId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        validatePage(page, size);
        return rankingService.getOffers(driverId, page, size);
    }

    @PostMapping("/match")
    RankingService.DriverMatch match(@RequestBody RankingService.FreightMatchRequest request) {
        return rankingService.matchDriver(request);
    }

    @PostMapping("/offers/{freightId}/accept")
    Map<String, String> acceptOffer(
            @PathVariable String freightId,
            @RequestParam String driverId) {
        rankingService.acceptOffer(driverId, freightId);
        return Map.of("freightId", freightId, "status", "ACCEPTED");
    }

    @PostMapping("/offers/{freightId}/hide")
    Map<String, String> hideOffer(
            @PathVariable String freightId,
            @RequestParam String driverId) {
        rankingService.hideOffer(driverId, freightId);
        return Map.of("freightId", freightId, "status", "HIDDEN");
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 20) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "page must be >= 0 and size must be between 1 and 20");
        }
    }
}
