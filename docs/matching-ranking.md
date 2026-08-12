# 직접 매칭 랭킹 명세

## 목표

화주에게는 조건이 가장 맞는 **기사 1명**을, 기사에게는 활동 지역과 최소수락운임에 맞는 **화물 20건**을 점수 순으로 보여준다. 단순히 지역이 일치하는지 필터링하는 것이 아니라 출발지·도착지·운임을 함께 반영한다.

이 문서의 점수식은 현재 in-memory 구현과 AI 장애 시 사용하는 결정적 기본 랭킹이다. PostgreSQL 전환 후에는 실제 좌표 거리로 기본 점수를 계산하고 상위 후보 50건만 AI가 재랭킹한다. 하이브리드 계약은 [`ai-route-ranking.md`](./ai-route-ranking.md), 거리 SQL은 [`postgres-distance-ranking.md`](./postgres-distance-ranking.md)를 따른다.

## 1. 필요한 데이터

### 기사 프로필

```json
{
  "id": "driver-01",
  "vehicleCargoTypes": ["REFRIGERATED", "GENERAL"],
  "currentSido": "서울특별시",
  "currentSigungu": "송파구",
  "preferredOriginSido": "서울특별시",
  "preferredOriginSigungu": "송파구",
  "preferredDestinationSido": "부산광역시",
  "preferredDestinationSigungu": "강서구",
  "minimumAcceptFareKrw": 420000
}
```

- `current*`: 현재 차량 위치. 화주의 상차지, 즉 기사의 출발지 근접도에 사용한다.
- `preferredOrigin*`, `preferredDestination*`: 기사 화면에서 입력·수정하는 활동 지역. 화물의 상·하차지와 비교한다.
- `minimumAcceptFareKrw`: 그 미만 운임은 추천 후보에서 제외한다.

### 화물 요청

```json
{
  "id": "freight-001",
  "originSido": "서울특별시",
  "originSigungu": "송파구",
  "destinationSido": "부산광역시",
  "destinationSigungu": "강서구",
  "cargoType": "REFRIGERATED",
  "offeredFareKrw": 500000
}
```

## 2. 후보 필터

아래 조건을 통과한 조합만 점수화한다.

1. 화물의 `cargoType`이 기사의 `vehicleCargoTypes`에 포함된다.
2. 화물 제시 운임이 기사 `minimumAcceptFareKrw` 이상이다.
3. 출발·도착 지역의 시/도 값이 존재한다. 파싱에서 빠진 요청은 화주가 보정한 뒤 재매칭하도록 한다.

## 3. 점수식 (100점)

| 항목 | 최대점 | 계산 기준 |
|---|---:|---|
| 상차지 근접도 | 40 | 현재 위치와 화물 상차지의 시/군/구 일치=40, 시/도만 일치=25, 인접 권역=12, 그 외=0 |
| 희망 출발지 일치 | 15 | 기사 활동 출발지와 화물 상차지의 시/군/구 일치=15, 시/도 일치=8, 그 외=0 |
| 희망 도착지 일치 | 25 | 기사 활동 도착지와 화물 하차지의 시/군/구 일치=25, 시/도 일치=13, 그 외=0 |
| 운임 적합도 | 20 | `(제시운임 - 최소수락운임) / 최소수락운임` 기준. 0~10%=10, 10~25%=15, 25% 이상=20 |

`인접 권역`은 현재 CSV/in-memory 폴백에서만 명시적인 시/군/구 쌍으로 판정한다. PostGIS 경로에서는 이를 실제 미터 거리 점수로 교체한다. 기본 랭킹 동점이면 **제시 운임이 높은 화물 → 화물 ID 오름차순**으로 고정한다.

### 의사 코드

```text
eligible = freights
  .filter(cargoTypeCompatible)
  .filter(offeredFareKrw >= minimumAcceptFareKrw)

score = pickupProximity(currentLocation, freight.origin)      // 0..40
      + preferredOriginMatch(activityOrigin, freight.origin)  // 0..15
      + preferredDestinationMatch(activityDestination, freight.destination) // 0..25
      + fareFit(offeredFareKrw, minimumAcceptFareKrw)          // 0..20

return eligible
  .sortBy(score DESC, offeredFareKrw DESC, freightId ASC)
  .slice(page * size, page * size + size) // 기본 size=20, 최대 20
```

## 4. 화면별 적용

### 기사 → 추천 화물

1. 기사는 출발·도착 활동 지역을 시/도 → 시/군/구 순서로 저장한다.
2. `GET /offers?driverId=driver-01&page=0&size=20`은 전체 후보의 점수를 먼저 정렬한 뒤 20건을 반환한다.
3. 기본 응답에는 `matchScore`와 `matchReasons`를 포함한다. 하이브리드 응답에는 `baseScore`·`aiScore`·`finalScore`·거리·`rankingMode`를 추가한다.
4. 화면은 첫 카드만 `✦ AI 최적 매칭`으로 강조하고 추천 근거를 표시한다.

```json
{
  "content": [
    {
      "freightId": "freight-109",
      "rank": 1,
      "matchScore": 100,
      "matchReasons": ["현재 위치와 상차지가 송파구로 일치", "희망 도착지 강서구 일치", "최소수락운임보다 102% 높은 운임"]
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 109,
  "totalPages": 6,
  "hasNext": true
}
```

### 페이징 규칙

- `page`는 0부터 시작하며, 기본값은 `0`이다.
- `size` 기본값과 최대값은 `20`이다. `size`가 20보다 크거나 음수 페이지를 요청하면 `400 Bad Request`를 반환한다.
- `rank`는 현재 페이지 번호가 아니라 전체 정렬 기준의 순위다. 두 번째 페이지의 첫 화물은 `rank: 21`이다.
- 본선 UI는 첫 페이지 20건을 스크롤 영역에 표시하면 충분하며, 페이지 번호·무한 스크롤 UI는 필수가 아니다. 단, API는 `hasNext`와 페이지 메타데이터를 반환해 이후 `더 보기`를 바로 붙일 수 있게 한다.
- 새로고침 버튼은 항상 `page=0&size=20`으로 다시 조회한다. 수락·숨김 후에도 같은 조건으로 재조회해 순위와 페이지 메타데이터를 갱신한다.

### 화주 → 최종 매칭 기사

`POST /match`는 같은 규칙을 화물 기준으로 각 기사에게 적용해 최고 점수 기사 1명을 반환한다. 화주 카드에 `matchScore`를 노출할 필요는 없지만, 데모에서는 `추천 이유`에 상차지 근접·도착지 선호·운임 충족을 보여줄 수 있다.

## 5. 핵심 테스트 시나리오

`driver-01`을 서울 송파 출발, 부산 강서 도착, 최소수락운임 42만원으로 두고 아래 필터·점수 차이를 검증한다. 500건 전체를 함께 정렬하므로 표의 번호는 절대 순위가 아니라 시나리오 간 우선순위다.

| 우선순위 | 화물 | 이유 |
|---:|---|---|
| 1 | 서울 송파 → 부산 강서, 냉장, 50만원 | 모든 지역 일치 + 최소수락운임 초과 |
| 2 | 서울 송파 → 부산 사상, 냉장, 48만원 | 상차지는 일치하나 도착지 근접도 낮음 |
| 3 | 서울 강동 → 부산 강서, 냉장, 55만원 | 운임은 높지만 상차지 완전 일치보다 낮음 |
| 제외 | 서울 송파 → 부산 강서, 냉장, 38만원 | 최소수락운임 미달 |
| 제외 | 서울 송파 → 부산 강서, 냉동, 55만원 | 차량 화물 유형 불일치 |

전체 데모 seed(기사 36명·화물 500건·시세 24건)와 페이지·운임 경고 시나리오는 [`mock-data.md`](./mock-data.md)를 따른다. 전체 화물 원본은 [`mock-freights.csv`](./mock-freights.csv)이며 백엔드도 같은 500건을 로드한다.

## 6. 범위 밖

- 실시간 GPS·도로 거리·통행료 기반 ETA. 기사 입력/mock 좌표의 PostGIS 직선거리 계산은 포함한다.
- 실시간 경매/입찰 및 기사 간 경쟁
- 수락 이력을 학습하는 ML 기반 개인화 랭킹. 자연어 선호를 이용한 LLM 재랭킹은 포함한다.

현재 백엔드는 설명 가능한 정적 랭킹을 폴백으로 유지한다. PostGIS와 AI 재랭킹을 붙여도 필수조건과 거리 근거는 서버가 통제하며, AI 장애가 사용자 추천 실패로 이어지지 않게 한다.
