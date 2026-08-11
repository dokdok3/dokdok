package com.dokdok.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RankingServiceTests {

    private final RankingService service = new RankingService();

    @Test
    void loadsExactlyFiveHundredMockFreights() {
        assertThat(service.mockFreightCount()).isEqualTo(500);
    }

    @Test
    void returnsTwentyRankedOffersAndPreservesGlobalRankOnNextPage() {
        RankingService.OfferPage firstPage = service.getOffers("driver-01", 0, 20);
        RankingService.OfferPage secondPage = service.getOffers("driver-01", 1, 20);

        assertThat(firstPage.content()).hasSize(20);
        assertThat(firstPage.totalElements()).isGreaterThan(20);
        assertThat(firstPage.totalPages()).isEqualTo((firstPage.totalElements() + 19) / 20);
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(firstPage.content().getFirst().rank()).isEqualTo(1);
        assertThat(secondPage.content()).isNotEmpty();
        assertThat(secondPage.content().getFirst().rank()).isEqualTo(21);
    }
}
