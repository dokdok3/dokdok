package com.dokdok.ranking;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RankingService {

    private final Map<String, DriverProfile> drivers = new ConcurrentHashMap<>();
    private final List<FreightOffer> freights;
    private final Map<String, Set<String>> hiddenFreightIdsByDriver = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> acceptedFreightIdsByDriver = new ConcurrentHashMap<>();

    public RankingService() {
        drivers.put("driver-01", new DriverProfile(
                "driver-01", "김도윤", Set.of("REFRIGERATED", "GENERAL"),
                "서울특별시", "송파구",
                new ActivityRegion("서울특별시", "송파구", "부산광역시", "강서구"),
                420_000));
        drivers.put("driver-02", new DriverProfile(
                "driver-02", "박서준", Set.of("GENERAL", "CONSTRUCTION"),
                "경기도", "수원시",
                new ActivityRegion("경기도", "수원시", "서울특별시", "송파구"),
                350_000));
        drivers.put("driver-03", new DriverProfile(
                "driver-03", "이하늘", Set.of("REFRIGERATED", "FROZEN"),
                "부산광역시", "강서구",
                new ActivityRegion("부산광역시", "강서구", "서울특별시", "송파구"),
                460_000));

        freights = createMockFreights();
    }

    public ActivityRegion getActivityRegion(String driverId) {
        return getDriver(driverId).activityRegion();
    }

    public ActivityRegion updateActivityRegion(String driverId, ActivityRegionUpdateRequest request) {
        if (isBlank(request.originSido()) || isBlank(request.originSigungu())
                || isBlank(request.destinationSido()) || isBlank(request.destinationSigungu())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "origin and destination regions are required");
        }

        DriverProfile driver = getDriver(driverId);
        ActivityRegion updated = new ActivityRegion(
                request.originSido(), request.originSigungu(),
                request.destinationSido(), request.destinationSigungu());
        drivers.put(driverId, driver.withActivityRegion(updated));
        return updated;
    }

    public OfferPage getOffers(String driverId, int page, int size) {
        DriverProfile driver = getDriver(driverId);
        Set<String> hiddenIds = hiddenFreightIdsByDriver.getOrDefault(driverId, Set.of());
        Set<String> acceptedIds = acceptedFreightIdsByDriver.getOrDefault(driverId, Set.of());

        List<RankedFreight> ranked = freights.stream()
                .filter(freight -> !hiddenIds.contains(freight.id()))
                .filter(freight -> isEligible(driver, freight))
                .map(freight -> toRankedFreight(driver, freight, acceptedIds.contains(freight.id())))
                .sorted(Comparator.comparingInt(RankedFreight::matchScore).reversed()
                        .thenComparing(RankedFreight::offeredFareKrw, Comparator.reverseOrder())
                        .thenComparing(RankedFreight::freightId))
                .toList();

        int totalElements = ranked.size();
        int fromIndex = Math.min(page * size, totalElements);
        int toIndex = Math.min(fromIndex + size, totalElements);
        List<RankedFreight> content = IntStream.range(fromIndex, toIndex)
                .mapToObj(index -> ranked.get(index).withRank(index + 1))
                .toList();

        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new OfferPage(content, page, size, totalElements, totalPages, page + 1 < totalPages);
    }

    public DriverMatch matchDriver(FreightMatchRequest request) {
        validateFreightRequest(request);

        return drivers.values().stream()
                .filter(driver -> isEligible(driver, request))
                .map(driver -> toDriverMatch(driver, request))
                .sorted(Comparator.comparingInt(DriverMatch::matchScore).reversed()
                        .thenComparing(DriverMatch::minimumAcceptFareKrw)
                        .thenComparing(DriverMatch::driverId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no matching driver found"));
    }

    public void acceptOffer(String driverId, String freightId) {
        DriverProfile driver = getDriver(driverId);
        FreightOffer freight = getFreight(freightId);
        if (!isEligible(driver, freight)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "freight is not eligible for this driver");
        }
        acceptedFreightIdsByDriver.computeIfAbsent(driverId, ignored -> ConcurrentHashMap.newKeySet()).add(freightId);
    }

    public void hideOffer(String driverId, String freightId) {
        getDriver(driverId);
        getFreight(freightId);
        hiddenFreightIdsByDriver.computeIfAbsent(driverId, ignored -> ConcurrentHashMap.newKeySet()).add(freightId);
    }

    private RankedFreight toRankedFreight(DriverProfile driver, FreightOffer freight, boolean accepted) {
        int pickupScore = pickupProximity(driver.currentSido(), driver.currentSigungu(), freight.originSido(), freight.originSigungu());
        int originScore = preferredMatch(
                driver.activityRegion().originSido(), driver.activityRegion().originSigungu(),
                freight.originSido(), freight.originSigungu(), 15, 8);
        int destinationScore = preferredMatch(
                driver.activityRegion().destinationSido(), driver.activityRegion().destinationSigungu(),
                freight.destinationSido(), freight.destinationSigungu(), 25, 13);
        int fareScore = fareFit(freight.offeredFareKrw(), driver.minimumAcceptFareKrw());
        int totalScore = pickupScore + originScore + destinationScore + fareScore;

        List<String> reasons = new ArrayList<>();
        if (pickupScore == 40) reasons.add("현재 위치와 상차지가 " + freight.originSigungu() + "로 일치");
        else if (pickupScore > 0) reasons.add("현재 위치와 상차지가 같은 시/도");
        if (destinationScore == 25) reasons.add("희망 도착지 " + freight.destinationSigungu() + " 일치");
        else if (destinationScore > 0) reasons.add("희망 도착지가 같은 시/도");
        reasons.add("최소수락운임보다 " + fareIncreasePercent(freight.offeredFareKrw(), driver.minimumAcceptFareKrw()) + "% 높은 운임");

        String fareStatus = freight.offeredFareKrw() < freight.averageFareKrw() ? "LOW" : "FAIR";
        return new RankedFreight(
                freight.id(), 0, totalScore, reasons,
                freight.originSido() + " " + freight.originSigungu(),
                freight.destinationSido() + " " + freight.destinationSigungu(),
                freight.cargoType(), freight.offeredFareKrw(), fareStatus,
                freight.averageFareKrw(), accepted ? "ACCEPTED" : "PENDING");
    }

    private DriverMatch toDriverMatch(DriverProfile driver, FreightMatchRequest request) {
        int pickupScore = pickupProximity(driver.currentSido(), driver.currentSigungu(), request.originSido(), request.originSigungu());
        int originScore = preferredMatch(
                driver.activityRegion().originSido(), driver.activityRegion().originSigungu(),
                request.originSido(), request.originSigungu(), 15, 8);
        int destinationScore = preferredMatch(
                driver.activityRegion().destinationSido(), driver.activityRegion().destinationSigungu(),
                request.destinationSido(), request.destinationSigungu(), 25, 13);
        int fareScore = fareFit(request.offeredFareKrw(), driver.minimumAcceptFareKrw());
        List<String> reasons = new ArrayList<>();
        if (pickupScore > 0) reasons.add("상차지 근접");
        if (destinationScore > 0) reasons.add("선호 도착지 일치");
        reasons.add("최소수락운임 충족");

        return new DriverMatch(
                driver.id(), driver.name(), pickupScore + originScore + destinationScore + fareScore,
                reasons, driver.minimumAcceptFareKrw(), driver.currentSido() + " " + driver.currentSigungu(),
                driver.activityRegion());
    }

    private boolean isEligible(DriverProfile driver, FreightOffer freight) {
        return driver.vehicleCargoTypes().contains(freight.cargoType())
                && freight.offeredFareKrw() >= driver.minimumAcceptFareKrw();
    }

    private boolean isEligible(DriverProfile driver, FreightMatchRequest request) {
        return driver.vehicleCargoTypes().contains(request.cargoType())
                && request.offeredFareKrw() >= driver.minimumAcceptFareKrw();
    }

    private int pickupProximity(String currentSido, String currentSigungu, String originSido, String originSigungu) {
        if (same(currentSido, originSido) && same(currentSigungu, originSigungu)) return 40;
        if (same(currentSido, originSido)) return 25;
        return isAdjacentDistrict(currentSigungu, originSigungu) ? 12 : 0;
    }

    private int preferredMatch(
            String preferredSido, String preferredSigungu,
            String targetSido, String targetSigungu,
            int exactScore, int sidoScore) {
        if (same(preferredSido, targetSido) && same(preferredSigungu, targetSigungu)) return exactScore;
        return same(preferredSido, targetSido) ? sidoScore : 0;
    }

    private int fareFit(long offeredFareKrw, long minimumAcceptFareKrw) {
        double increase = (double) (offeredFareKrw - minimumAcceptFareKrw) / minimumAcceptFareKrw;
        if (increase >= 0.25) return 20;
        if (increase >= 0.10) return 15;
        return 10;
    }

    private int fareIncreasePercent(long offeredFareKrw, long minimumAcceptFareKrw) {
        return (int) Math.round((double) (offeredFareKrw - minimumAcceptFareKrw) / minimumAcceptFareKrw * 100);
    }

    private boolean isAdjacentDistrict(String first, String second) {
        return Set.of("송파구:강동구", "강동구:송파구", "강서구:사상구", "사상구:강서구")
                .contains(first + ":" + second);
    }

    private DriverProfile getDriver(String driverId) {
        DriverProfile driver = drivers.get(driverId);
        if (driver == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "driver not found: " + driverId);
        return driver;
    }

    private FreightOffer getFreight(String freightId) {
        return freights.stream()
                .filter(freight -> freight.id().equals(freightId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "freight not found: " + freightId));
    }

    private void validateFreightRequest(FreightMatchRequest request) {
        if (request == null || isBlank(request.cargoType()) || isBlank(request.originSido())
                || isBlank(request.originSigungu()) || isBlank(request.destinationSido())
                || isBlank(request.destinationSigungu()) || request.offeredFareKrw() == null
                || request.offeredFareKrw() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cargo type, regions, and offered fare are required");
        }
    }

    private List<FreightOffer> createMockFreights() {
        List<FreightOffer> result = new ArrayList<>();
        result.add(new FreightOffer("freight-01", "서울특별시", "송파구", "부산광역시", "강서구", "REFRIGERATED", 500_000, 520_000));
        result.add(new FreightOffer("freight-02", "서울특별시", "송파구", "부산광역시", "사상구", "REFRIGERATED", 480_000, 500_000));
        result.add(new FreightOffer("freight-03", "서울특별시", "강동구", "부산광역시", "강서구", "REFRIGERATED", 550_000, 540_000));

        String[] originDistricts = {"송파구", "강동구", "강남구", "마포구", "수원시"};
        String[] destinationDistricts = {"강서구", "사상구", "해운대구", "수영구", "수원시"};
        IntStream.rangeClosed(4, 27).forEach(number -> {
            String originDistrict = originDistricts[(number - 4) % originDistricts.length];
            String destinationDistrict = destinationDistricts[(number - 4) % destinationDistricts.length];
            String originSido = originDistrict.equals("수원시") ? "경기도" : "서울특별시";
            String destinationSido = destinationDistrict.equals("수원시") ? "경기도" : "부산광역시";
            String cargoType = number % 3 == 0 ? "GENERAL" : "REFRIGERATED";
            long offeredFare = 430_000L + (number % 6) * 20_000L;
            result.add(new FreightOffer(
                    "freight-%02d".formatted(number), originSido, originDistrict,
                    destinationSido, destinationDistrict, cargoType, offeredFare, 500_000));
        });

        result.add(new FreightOffer("freight-low-fare", "서울특별시", "송파구", "부산광역시", "강서구", "REFRIGERATED", 380_000, 520_000));
        result.add(new FreightOffer("freight-frozen", "서울특별시", "송파구", "부산광역시", "강서구", "FROZEN", 550_000, 520_000));
        return List.copyOf(result);
    }

    private boolean same(String first, String second) {
        return first != null && first.equals(second);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record ActivityRegion(String originSido, String originSigungu, String destinationSido, String destinationSigungu) {
    }

    public record ActivityRegionUpdateRequest(
            String originSido, String originSigungu,
            String destinationSido, String destinationSigungu) {
    }

    public record FreightMatchRequest(
            String cargoType,
            String originSido, String originSigungu,
            String destinationSido, String destinationSigungu,
            Long offeredFareKrw) {
    }

    public record RankedFreight(
            String freightId, int rank, int matchScore, List<String> matchReasons,
            String origin, String destination, String cargoType, long offeredFareKrw,
            String fareStatus, long averageFareKrw, String status) {
        RankedFreight withRank(int value) {
            return new RankedFreight(
                    freightId, value, matchScore, matchReasons, origin, destination,
                    cargoType, offeredFareKrw, fareStatus, averageFareKrw, status);
        }
    }

    public record OfferPage(
            List<RankedFreight> content, int page, int size,
            int totalElements, int totalPages, boolean hasNext) {
    }

    public record DriverMatch(
            String driverId, String driverName, int matchScore, List<String> matchReasons,
            long minimumAcceptFareKrw, String currentLocation, ActivityRegion activityRegion) {
    }

    private record DriverProfile(
            String id, String name, Set<String> vehicleCargoTypes,
            String currentSido, String currentSigungu,
            ActivityRegion activityRegion, long minimumAcceptFareKrw) {
        DriverProfile withActivityRegion(ActivityRegion updated) {
            return new DriverProfile(id, name, vehicleCargoTypes, currentSido, currentSigungu, updated, minimumAcceptFareKrw);
        }
    }

    private record FreightOffer(
            String id, String originSido, String originSigungu,
            String destinationSido, String destinationSigungu,
            String cargoType, long offeredFareKrw, long averageFareKrw) {
    }
}
