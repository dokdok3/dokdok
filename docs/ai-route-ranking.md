# 기사 경로 기반 AI 화물 랭킹 명세

> 상태: 목표 설계. 현재 백엔드는 CSV/in-memory 기본 랭킹만 구현돼 있으며, 이 문서는
> PostGIS와 OpenAI 재랭킹을 붙일 때의 구현 계약이다.

## 1. 결정

기사 추천은 **PostgreSQL/PostGIS 후보 검색 + OpenAI 재랭킹**의 2단계 구조로 만든다.
500건 전체를 모델에 보내지 않는다. 데이터베이스가 운송 불가능한 화물을 제외하고 거리와
정적 점수로 상위 50건을 만든 뒤, AI는 그 후보만 기사 의도에 맞게 재평가한다.

```text
기사의 현재 위치·희망 도착지·차량 조건·자연어 선호
  → PostGIS 필수조건/반경 필터
  → DB 기본 점수 순 후보 50건
  → OpenAI Structured Outputs 재랭킹
  → 기본 점수 80% + AI 점수 20%
  → 최종 상위 20건과 추천 근거 반환
```

역할을 다음처럼 분리한다.

| 담당 | 판단 내용 |
|---|---|
| PostgreSQL/PostGIS | 실제 좌표 거리, 반경 포함 여부, 차량 종류, 적재 중량, 최소 운임, 상차 가능 시간 |
| AI | `부산 서부권 선호`, `단가가 조금 낮아도 귀가 방향 우선` 같은 자연어 선호와 후보 간 상대적 장단점 |
| 백엔드 | 최종 점수 결합, 모델 응답 검증, 동점 처리, 페이징, 캐시, 실패 폴백 |

AI는 거리나 시간을 추측하지 않는다. `pickupDistanceM`, `destinationGapM`처럼 PostGIS가
계산한 숫자만 근거로 사용한다. 도로 주행거리·ETA·우회거리가 필요하면 길찾기 API 결과를
별도로 저장해 전달하며, 직선거리인 PostGIS 결과를 도로거리라고 표현하지 않는다.

## 2. 입력 데이터

기사의 구조화 조건에 선택적인 자연어 선호를 추가한다.

```json
{
  "driverId": "driver-01",
  "currentLocation": { "latitude": 37.5145, "longitude": 127.1059 },
  "preferredDestination": { "latitude": 35.2122, "longitude": 128.9806 },
  "vehicleCargoTypes": ["REFRIGERATED", "GENERAL"],
  "vehicleCapacityTon": 5,
  "minimumAcceptFareKrw": 500000,
  "availableFrom": "2026-08-13T06:00:00+09:00",
  "availableUntil": "2026-08-13T14:00:00+09:00",
  "pickupRadiusM": 50000,
  "destinationRadiusM": 100000,
  "preferenceText": "부산 서부권으로 가는 냉장 화물을 우선하고 상차지가 가까우면 좋겠어요"
}
```

`preferenceText`가 비어 있거나 AI 호출을 생략하면 PostGIS 기본 순위를 그대로 반환한다.
주소를 좌표로 바꾸는 지오코딩은 저장 시 한 번 수행하고, 추천 요청마다 반복하지 않는다.

## 3. 1차 후보 검색

PostGIS 단계는 전체 500건에서 다음 조건을 순서대로 적용한다.

1. `PENDING` 상태이고 기사에게 숨김 처리되지 않은 화물
2. 차량이 지원하는 화물 종류
3. 차량 적재 중량 이하
4. 제시 운임이 기사의 최소수락운임 이상
5. 기사의 운행 가능 시간과 상차 시간이 일치
6. 현재 위치에서 상차지가 설정 반경 이내
7. 화물 도착지가 기사 희망 도착지 반경 이내
8. 거리·운임·시간 점수로 정렬해 상위 50건 선택

구체적인 스키마와 SQL은 [`postgres-distance-ranking.md`](./postgres-distance-ranking.md)를 따른다.

## 4. AI 재랭킹 계약

AI에는 기사 조건과 후보 50건의 압축된 필드만 전달한다. 화물 설명 원문, 전체 500건,
불필요한 개인정보는 보내지 않는다.

```json
{
  "driverPreference": "부산 서부권으로 가는 냉장 화물을 우선",
  "candidates": [
    {
      "freightId": "freight-109",
      "cargoType": "REFRIGERATED",
      "offeredFareKrw": 850000,
      "loadingAt": "2026-08-13T08:00:00+09:00",
      "pickupDistanceM": 4200,
      "destinationGapM": 3100,
      "baseScore": 94.2
    }
  ]
}
```

Responses API의 Structured Outputs를 사용해 아래 형태로만 받는다. JSON Schema의 모든
필드를 `required`로 두고 객체에는 `additionalProperties: false`를 적용한다.
[OpenAI 공식 Structured Outputs 문서](https://developers.openai.com/api/docs/guides/structured-outputs)

```json
{
  "rankings": [
    {
      "freightId": "freight-109",
      "aiScore": 96,
      "reasons": [
        "상차지가 현재 위치에서 가깝습니다",
        "희망 도착 권역과 일치하는 냉장 화물입니다"
      ],
      "concerns": []
    }
  ]
}
```

프롬프트의 핵심 규칙은 다음과 같다.

```text
당신은 화물 기사의 자연어 운행 선호를 후보 화물과 비교하는 재랭커다.
후보에 제공된 숫자와 사실만 사용하고 실제 거리·주소·운임을 추측하지 않는다.
차량 종류·중량·최소 운임 등 필수조건을 뒤집지 않는다.
입력에 없는 freightId를 만들지 않는다.
모든 후보를 정확히 한 번씩 반환하고 aiScore는 0~100 정수로 제한한다.
추천 이유는 기사 선호와 제공된 수치에 근거해 최대 3개만 작성한다.
```

모델 응답을 신뢰해서 바로 노출하지 않는다. 백엔드는 다음을 검증한다.

- 요청한 후보 ID 집합과 응답 ID 집합이 정확히 같은지
- 중복 ID나 알 수 없는 ID가 없는지
- 점수가 0~100인지
- 이유와 우려의 개수·문자열 길이가 제한 이내인지

검증에 실패하면 응답 전체를 버리고 `RULE_FALLBACK`으로 처리한다.

## 5. 최종 점수와 정렬

후보 50건 안에서는 아래 식으로 최종 순위를 만든다.

```text
finalScore = baseScore × 0.8 + aiScore × 0.2
```

동점은 `baseScore DESC → offeredFareKrw DESC → freightId ASC` 순서로 해소한다.
AI가 필수조건을 통과하지 못한 화물을 다시 살릴 수는 없다. AI가 지나치게 높은 점수를 줘도
전체 영향은 20%로 제한되므로 정확한 거리·운임 조건이 우선한다.

API 응답에는 판단 근거를 분리해서 제공한다.

```json
{
  "rankingMode": "HYBRID",
  "content": [
    {
      "freightId": "freight-109",
      "rank": 1,
      "baseScore": 94.2,
      "aiScore": 96,
      "finalScore": 94.56,
      "pickupDistanceKm": 4.2,
      "destinationGapKm": 3.1,
      "matchReasons": [
        "상차지까지 4.2km",
        "희망 도착지에서 3.1km",
        "선호하는 냉장 화물"
      ]
    }
  ]
}
```

## 6. 페이징과 캐시

- PostGIS가 만든 전체 기본 순서는 유지하고, 상위 후보 50건만 AI가 재정렬한다.
- AI로 정렬한 50건 뒤에는 나머지 후보를 기존 `baseScore` 순서로 붙여 전체 페이지를 만든다.
- 기사 ID·위치·희망 목적지·차량 조건·선호 문장의 해시를 `rankingKey`로 사용한다.
- 동일한 `rankingKey`의 결과를 5분 캐시해 페이지를 넘겨도 순위가 바뀌지 않게 한다.
- 활동 지역·최소 운임·자연어 선호를 수정하거나 새로고침을 명시적으로 누르면 새 랭킹을 만든다.
- 화면은 기존처럼 페이지당 최대 20건을 표시한다.

## 7. 실패와 비용 통제

| 상황 | 처리 |
|---|---|
| AI 타임아웃·한도 초과 | PostGIS 기본 순위 반환, `rankingMode: RULE_FALLBACK` |
| 후보가 0건 | AI를 호출하지 않고 빈 페이지 반환 |
| 후보가 1건 | AI를 호출하지 않고 해당 화물 반환 |
| 자연어 선호 없음 | AI 호출을 생략할 수 있으며 기본 추천 이유는 템플릿으로 생성 |
| 잘못된 AI 응답 | 전체 응답 폐기 후 기본 순위 사용 |

로그에는 API 키와 기사 자연어 원문을 남기지 않는다. 운영 지표로는 후보 수, 모델 입력 토큰,
응답시간, 폴백 비율, 수락률을 기록한다.

## 8. 최소 검증

1. 동일한 기사 조건과 동일한 모델 응답에는 항상 같은 최종 순위가 나오는지
2. 최소 운임 미달·중량 초과 화물이 AI 결과에 포함되지 않는지
3. AI가 존재하지 않는 ID를 반환하면 기본 순위로 폴백하는지
4. AI 장애 때도 `GET /offers`가 정상적인 20건 페이지를 반환하는지
5. 거리상 유리한 후보가 단순 자연어 점수만으로 과도하게 뒤집히지 않는지
6. 추천 이유의 거리 숫자가 PostGIS 계산 결과와 일치하는지
