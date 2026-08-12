# PostgreSQL/PostGIS 거리 기반 후보 랭킹 설계

> 상태: 목표 설계. 현재 백엔드에는 PostgreSQL/PostGIS 연동이 없으며 아래 스키마·SQL은
> 다음 구현 단계에서 적용한다.

## 1. 목적과 범위

기사가 입력한 현재 출발 위치와 희망 도착 위치를 기준으로 화물의 상차지·하차지 거리를
계산하고, 전체 화물에서 AI에 전달할 후보 50건을 만든다. PostgreSQL 단독의 문자열 지역
비교가 아니라 **PostGIS `geography(Point, 4326)`** 를 사용한다.

`geography`의 `ST_Distance`와 `ST_DWithin`은 거리를 미터 단위로 다룬다. 반경 검색에는
공간 인덱스를 활용하는 `ST_DWithin`을 먼저 사용하고, 통과한 후보에만 `ST_Distance`를
계산한다. 전체 행에 `ST_Distance(...) < 반경`을 적용하는 방식은 피한다.

- [PostGIS `ST_DWithin` 공식 문서](https://postgis.net/docs/ST_DWithin.html)
- [PostGIS `ST_Distance` 공식 문서](https://postgis.net/docs/ST_Distance.html)
- [PostGIS 공간 인덱스 공식 문서](https://postgis.net/docs/using_postgis_query.html#using_postgis_query_index)

## 2. 확장과 테이블 예시

아래는 목표 스키마다. 현재 in-memory mock 구현을 PostgreSQL로 옮길 때 Flyway 마이그레이션으로
적용한다. `ST_MakePoint`의 인자 순서는 **경도(longitude), 위도(latitude)** 다.

```sql
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE driver_route_preference (
    driver_id                 varchar(50) PRIMARY KEY,
    current_location          geography(Point, 4326) NOT NULL,
    preferred_destination     geography(Point, 4326) NOT NULL,
    vehicle_cargo_types       text[] NOT NULL,
    vehicle_capacity_ton      numeric(6, 2) NOT NULL,
    minimum_accept_fare_krw   bigint NOT NULL,
    available_from            timestamptz NOT NULL,
    available_until           timestamptz NOT NULL,
    pickup_radius_m           integer NOT NULL DEFAULT 50000,
    destination_radius_m      integer NOT NULL DEFAULT 100000,
    preference_text           text,
    updated_at                timestamptz NOT NULL DEFAULT now(),
    CHECK (vehicle_capacity_ton > 0),
    CHECK (minimum_accept_fare_krw >= 0),
    CHECK (pickup_radius_m BETWEEN 1000 AND 200000),
    CHECK (destination_radius_m BETWEEN 1000 AND 300000),
    CHECK (available_from <= available_until)
);

CREATE TABLE freight_offer (
    id                        varchar(50) PRIMARY KEY,
    cargo_type                varchar(30) NOT NULL,
    cargo_description         varchar(200) NOT NULL,
    origin_sido               varchar(30) NOT NULL,
    origin_sigungu            varchar(30) NOT NULL,
    destination_sido          varchar(30) NOT NULL,
    destination_sigungu       varchar(30) NOT NULL,
    weight_ton                numeric(6, 2) NOT NULL,
    offered_fare_krw          bigint NOT NULL,
    loading_at                timestamptz NOT NULL,
    unloading_at              timestamptz NOT NULL,
    origin_location           geography(Point, 4326) NOT NULL,
    destination_location      geography(Point, 4326) NOT NULL,
    status                    varchar(20) NOT NULL DEFAULT 'PENDING',
    CHECK (weight_ton > 0),
    CHECK (offered_fare_krw >= 0),
    CHECK (loading_at <= unloading_at)
);

CREATE TABLE driver_hidden_offer (
    driver_id                 varchar(50) NOT NULL REFERENCES driver_route_preference(driver_id),
    freight_id                varchar(50) NOT NULL REFERENCES freight_offer(id),
    hidden_at                 timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (driver_id, freight_id)
);
```

주소와 화면 표시용 행정구역 문자열은 유지하되 추천 계산의 기준은 좌표로 한다. 문자열은
표시·검색 보조 용도다.

좌표 저장 예시는 다음과 같다.

```sql
UPDATE driver_route_preference
SET current_location = ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography
WHERE driver_id = :driverId;
```

### 기존 500건 CSV에 좌표 넣기

현재 [`mock-freights.csv`](./mock-freights.csv)는 시·도와 시·군·구만 있으므로 바로 거리 계산을
할 수 없다. [`korea-admin-area-coordinates.csv`](./korea-admin-area-coordinates.csv)에
2026-08-13 기준 시도 16개와 시군구 269개(최하위 256개)의 검증된 WGS84 대표 좌표를 준비했다.
산출 기준과 전체 적재 SQL은 [`korea-admin-area-coordinates.md`](./korea-admin-area-coordinates.md)를
따른다.

```sql
CREATE TABLE region_coordinate (
    sido                      varchar(30) NOT NULL,
    sigungu                   varchar(30) NOT NULL,
    location                  geography(Point, 4326) NOT NULL,
    PRIMARY KEY (sido, sigungu)
);

INSERT INTO region_coordinate (sido, sigungu, location)
VALUES (
    '서울특별시',
    '송파구',
    ST_SetSRID(ST_MakePoint(127.1058583, 37.5048645), 4326)::geography
);
```

CSV를 임시 테이블에 적재한 뒤 출발·도착 행정구역을 `region_coordinate`와 조인해
`origin_location`과 `destination_location`을 채운다. 데모의 구 단위 추천은 대표 좌표로
충분하지만, 실제 주소가 들어오는 운영 환경에서는 지오코딩한 주소 좌표를 저장해야 한다.
서버 시작 때 500건을 매번 지오코딩하지 않는다.

## 3. 인덱스

```sql
CREATE INDEX freight_offer_origin_gix
    ON freight_offer USING GIST (origin_location);

CREATE INDEX freight_offer_destination_gix
    ON freight_offer USING GIST (destination_location);

CREATE INDEX freight_offer_candidate_idx
    ON freight_offer (status, cargo_type, loading_at, offered_fare_krw);
```

GiST 인덱스는 반경 후보를 줄이는 데 사용하고, B-tree 인덱스는 상태·종류·시간·운임 필터를
보조한다. 데이터 분포에 따라 PostgreSQL이 어느 인덱스를 사용할지는 달라지므로 실제 seed와
운영 데이터에서 `EXPLAIN (ANALYZE, BUFFERS)`로 확인한다.

## 4. 후보 50건 SQL

기본 점수는 100점이며 구성은 다음과 같다.

| 항목 | 최대점 | 계산 |
|---|---:|---|
| 상차지 거리 | 35 | 0m=35점, `pickupRadiusM` 경계=0점으로 선형 감소 |
| 희망 도착지 거리 | 25 | 0m=25점, `destinationRadiusM` 경계=0점으로 선형 감소 |
| 운임 | 20 | 최소수락운임 충족=10점, 25% 이상 높으면 20점 |
| 화물 종류·중량 | 10 | 필수 필터를 통과하면 10점 |
| 상차 시간 | 10 | 가능한 시작 시각에 가까울수록 높고 10시간 이후 0점 |

```sql
WITH driver_ctx AS (
    SELECT *
    FROM driver_route_preference
    WHERE driver_id = :driverId
),
eligible AS (
    SELECT
        f.*,
        d.minimum_accept_fare_krw,
        d.available_from,
        d.pickup_radius_m,
        d.destination_radius_m,
        ST_Distance(d.current_location, f.origin_location) AS pickup_distance_m,
        ST_Distance(d.preferred_destination, f.destination_location) AS destination_gap_m
    FROM freight_offer f
    CROSS JOIN driver_ctx d
    WHERE f.status = 'PENDING'
      AND f.cargo_type = ANY (d.vehicle_cargo_types)
      AND f.weight_ton <= d.vehicle_capacity_ton
      AND f.offered_fare_krw >= d.minimum_accept_fare_krw
      AND f.loading_at BETWEEN d.available_from AND d.available_until
      AND ST_DWithin(f.origin_location, d.current_location, d.pickup_radius_m)
      AND ST_DWithin(f.destination_location, d.preferred_destination, d.destination_radius_m)
      AND NOT EXISTS (
          SELECT 1
          FROM driver_hidden_offer h
          WHERE h.driver_id = d.driver_id
            AND h.freight_id = f.id
      )
),
scored AS (
    SELECT
        e.*,
        GREATEST(0.0, 35.0 * (1.0 - pickup_distance_m / pickup_radius_m)) AS pickup_score,
        GREATEST(0.0, 25.0 * (1.0 - destination_gap_m / destination_radius_m)) AS destination_score,
        LEAST(
            20.0,
            10.0 + 40.0 * (offered_fare_krw - minimum_accept_fare_krw)
                 / GREATEST(minimum_accept_fare_krw, 1)
        ) AS fare_score,
        10.0 AS cargo_score,
        GREATEST(
            0.0,
            10.0 - EXTRACT(EPOCH FROM (loading_at - available_from)) / 3600.0
        ) AS time_score
    FROM eligible e
)
SELECT
    id AS freight_id,
    cargo_type,
    offered_fare_krw,
    loading_at,
    ROUND(pickup_distance_m)::bigint AS pickup_distance_m,
    ROUND(destination_gap_m)::bigint AS destination_gap_m,
    ROUND(
        (pickup_score + destination_score + fare_score + cargo_score + time_score)::numeric,
        2
    ) AS base_score
FROM scored
ORDER BY
    base_score DESC,
    offered_fare_krw DESC,
    freight_id ASC
LIMIT 50;
```

본선에서 숨김 상태를 메모리로 유지한다면 `driver_hidden_offer`의 `NOT EXISTS` 절은 서비스
계층 필터로 대체할 수 있다.

## 5. 거리의 의미

이 쿼리가 계산하는 값은 다음 두 가지다.

```text
pickupDistance = 기사 현재 위치 → 화물 상차지의 지표면 최단거리
destinationGap = 화물 하차지 → 기사 희망 도착지의 지표면 최단거리
```

이는 도로망을 따른 주행거리나 ETA가 아니다. 방향 적합도를 더 정밀하게 보려면 다음 값을
길찾기 API에서 받아 별도 컬럼 또는 짧은 TTL 캐시에 보관한다.

- 상차지까지 예상 주행시간
- 기사 희망 경로 대비 추가 우회거리
- 화물 운송 경로의 예상 주행거리·시간
- 통행료

AI에는 PostGIS 거리와 길찾기 API 값을 구분된 필드로 전달한다.

## 6. 페이징 안정성

추천 요청 시 전체 후보의 기본 순위를 먼저 확정하고 `baseScore DESC`, `offeredFareKrw DESC`,
`freightId ASC`를 항상 적용한다. AI가 상위 50건을 재정렬한 결과는 `rankingKey`와 함께 캐시해
같은 랭킹 실행의 1·2페이지가 서로 다른 모델 호출 결과를 사용하지 않게 한다.

화물이 새로 등록되더라도 이미 보고 있는 페이지가 흔들리지 않게 하려면 `OFFSET`만 쓰기보다
다음 중 하나를 선택한다.

1. 해커톤 MVP: 5분 동안 랭킹 결과 ID 목록을 캐시하고 그 목록을 페이지 단위로 자른다.
2. 운영 버전: `ranking_run`과 `ranking_run_item` 테이블에 순위 스냅샷을 저장한다.

## 7. 검증 방법

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT id
FROM freight_offer
WHERE status = 'PENDING'
  AND ST_DWithin(
      origin_location,
      ST_SetSRID(ST_MakePoint(127.1059, 37.5145), 4326)::geography,
      50000
  );
```

최소한 다음을 확인한다.

- 실행 계획에서 `freight_offer_origin_gix`가 사용되는지
- 0m·반경 경계·반경 밖 화물의 점수가 예상대로인지
- 위도와 경도를 뒤집어 저장하지 않았는지
- 같은 조건에서 순위와 동점 처리 결과가 반복 실행마다 같은지
- 서울→부산처럼 장거리 구간에서도 단위가 미터로 일관되는지
- PostGIS 후보 50건과 AI에 전달한 후보 ID가 정확히 일치하는지

## 8. 현재 백엔드 이행 순서

현재 [`backend/build.gradle.kts`](../backend/build.gradle.kts)는 MySQL 드라이버를 포함하고 DB
자동설정을 제외하고 있다. PostgreSQL 경로를 구현할 때 다음 순서로 변경한다.

1. `mysql-connector-j`를 PostgreSQL JDBC 드라이버로 교체한다.
2. JPA에서 공간 타입을 매핑하려면 `org.hibernate.orm:hibernate-spatial`을 추가한다.
3. Flyway와 PostgreSQL용 Flyway 모듈을 추가하고 위 DDL을 버전 마이그레이션으로 만든다.
4. `application.yaml`의 JDBC/JPA 자동설정 제외 항목을 제거하고 datasource 환경변수를 연결한다.
5. PostGIS가 포함된 PostgreSQL 컨테이너에서 스키마와 500건 seed를 적재한다.
6. 기존 `RankingService`의 in-memory 스트림 필터를 Repository의 후보 SQL로 교체한다.
7. AI 재랭킹을 붙이기 전 PostGIS 기본 순위와 폴백 테스트를 먼저 통과시킨다.

의존성의 형태는 다음과 같다. 구체적인 버전은 Spring Boot dependency management에 맞춘다.

```kotlin
implementation("org.springframework.boot:spring-boot-starter-data-jpa")
implementation("org.hibernate.orm:hibernate-spatial")
implementation("org.flywaydb:flyway-core")
runtimeOnly("org.flywaydb:flyway-database-postgresql")
runtimeOnly("org.postgresql:postgresql")
```
