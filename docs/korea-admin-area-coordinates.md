# 대한민국 시도·시군구 대표 좌표

> 기준일: 2026-08-13  
> 결과 파일: [`korea-admin-area-coordinates.csv`](./korea-admin-area-coordinates.csv)  
> 범위: 시도 16개, 시군구 269개(최하위 256개), 총 285행

기사의 출발지·희망 도착지와 화물의 상·하차지를 대표 좌표로 바꾸기 위한 seed다. 좌표계는
GPS·PostGIS에서 바로 쓰는 **WGS84(EPSG:4326)** 이며, 각 행의 `latitude`는 위도,
`longitude`는 경도다.

## 1. 포함 기준

- `SIDO`: 현재 법정동 코드에 존재하는 16개 시도
- `SIGUNGU`: 현재 법정동 코드의 시군구 5자리 단계 269개 전체
- 일반구가 있는 `수원시` 같은 상위 시 13개와 `수원시 장안구` 같은 최하위 지역을 모두
  제공한다. 정확한 구 선택 화면은 `is_leaf=true`인 256개만 노출하고, 시 단위 광역 검색은
  상위 시 행도 사용할 수 있다.
- 세종특별자치시는 시도이면서 단일 시군구 역할도 하므로 `SIDO(36)`와
  `SIGUNGU(36110)`에 각각 한 번씩 들어간다.

최근 시행된 다음 변경을 반영했다.

- 광주광역시와 전라남도 → `전남광주통합특별시`
- 인천 중구·동구·서구 개편 → `제물포구`, `영종구`, `서해구`, `검단구`
- 2026-02-01 화성시 일반구 신설 → `만세구`, `효행구`, `병점구`, `동탄구`

광주·전남 통합과 인천 개편은 2026-07-01 시행이다. 과거 명칭인 `광주광역시`,
`전라남도`, 인천 `중구`·`동구`·`서구`는 현재 선택 목록에서 제외했다. `화성시`는
상위 검색 단위로 유지하되 최하위 선택지는 신설된 4개 구다.

## 2. 시도별 건수와 대표 좌표

| 코드 | 시도 | 시군구 수 | 위도 | 경도 |
|---:|---|---:|---:|---:|
| 11 | 서울특별시 | 25 | 37.5644811 | 126.9396081 |
| 12 | 전남광주통합특별시 | 27 | 34.8914813 | 127.0015026 |
| 26 | 부산광역시 | 16 | 35.2111695 | 129.0539550 |
| 27 | 대구광역시 | 9 | 35.9649200 | 128.6496004 |
| 28 | 인천광역시 | 11 | 37.7057229 | 126.4391698 |
| 30 | 대전광역시 | 5 | 36.3416758 | 127.3874750 |
| 31 | 울산광역시 | 5 | 35.5267192 | 129.2147662 |
| 36 | 세종특별자치시 | 1 | 36.5702933 | 127.2697257 |
| 41 | 경기도 | 47 | 37.5884751 | 127.4083580 |
| 43 | 충청북도 | 14 | 36.6359042 | 127.5720191 |
| 44 | 충청남도 | 16 | 36.5220663 | 126.8699606 |
| 47 | 경상북도 | 23 | 36.3620705 | 128.6367852 |
| 48 | 경상남도 | 22 | 35.3683857 | 128.3742292 |
| 50 | 제주특별자치도 | 2 | 33.3805172 | 126.5474422 |
| 51 | 강원특별자치도 | 18 | 37.8236727 | 128.2089912 |
| 52 | 전북특별자치도 | 15 | 35.7282685 | 127.1228070 |

시군구 269개의 좌표와 코드는 표 대신 CSV에 전부 기록했다.

## 3. 좌표 산출법

좌표는 시청·구청 주소가 아니라 **행정구역 경계 안의 대표점**이다. 2025년 2분기 SGIS
경계를 원래 좌표계에서 계산한 뒤 WGS84로 변환했다. 단순 중심점은 섬이나 오목한 경계에서
구역 밖에 놓일 수 있으므로 Shapely `representative_point()`와 같은 point-on-surface 방식을
사용했다.

| `coordinate_method` | 의미 |
|---|---|
| `SGIS_2025_POINT_ON_SURFACE` | 해당 2025 시도·시군구 경계 안 대표점 |
| `SGIS_2025_SIDO_UNION_POINT_ON_SURFACE` | 옛 광주·전남 경계를 합친 뒤 구한 대표점 |
| `SGIS_2025_SIGUNGU_UNION_POINT_ON_SURFACE` | 일반구 경계를 합쳐 상위 시 대표점을 산출 |
| `SGIS_2025_DONG_UNION_POINT_ON_SURFACE` | 2026년 신설 구 관할 행정동 경계를 합친 뒤 구한 대표점 |

`source_boundary_codes`에는 좌표 계산에 사용한 SGIS 경계 코드를 `|`로 연결해 남겼다.
CSV의 `area_code`는 법정동 코드 앞 2자리 또는 5자리이고, SGIS 경계 코드는 별도 체계이므로
서로 같은 숫자라고 가정하면 안 된다.

신설 구의 관할은 공식 목록을 사용했다. 2025 경계 이후 분동된 인천 `운서1·2동`은 옛
`운서동`, `아라1·2동`은 옛 `아라동` 경계를 사용했다. 분동은 상위 구의 외곽선을 바꾸지
않으므로 구 대표점 산출에는 영향이 없다.

## 4. CSV 열

| 열 | 설명 |
|---|---|
| `area_level` | `SIDO` 또는 `SIGUNGU` |
| `area_code` | 현재 법정동 시도 2자리/시군구 5자리 코드 |
| `parent_code` | 시군구가 속한 시도 2자리 코드 |
| `area_type` | 시·도·군·구 등 유형 |
| `is_leaf` | 더 하위 일반구가 없는 실제 최하위 시군구인지 여부 |
| `sido_name` | 현재 시도명 |
| `sigungu_name` | 화면에 표시할 시군구명. 일반구는 `수원시 장안구`처럼 상위 시 포함 |
| `full_name` | 시도와 시군구를 합친 고유 이름 |
| `latitude`, `longitude` | WGS84 대표 좌표 |
| `coordinate_method` | 위 좌표 산출 방식 |
| `source_boundary_codes` | 산출에 사용한 SGIS 경계 코드 |
| `boundary_base_date` | 원본 경계 기준일. 현재 모두 `2025-06-30` |

## 5. PostGIS 적재

`area_code`는 숫자가 아니라 문자열로 저장해야 앞의 0과 코드 체계를 안전하게 유지할 수 있다.
`ST_MakePoint`의 인자는 **경도, 위도** 순서다.

```sql
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE region_coordinate_stage (
    area_level             text,
    area_code              text,
    parent_code            text,
    area_type              text,
    is_leaf                boolean,
    sido_name              text,
    sigungu_name           text,
    full_name              text,
    latitude               double precision,
    longitude              double precision,
    coordinate_method      text,
    source_boundary_codes  text,
    boundary_base_date     date
);

\copy region_coordinate_stage
FROM 'docs/korea-admin-area-coordinates.csv'
WITH (FORMAT csv, HEADER true, ENCODING 'UTF8');

CREATE TABLE region_coordinate AS
SELECT
    area_level,
    area_code,
    NULLIF(parent_code, '') AS parent_code,
    area_type,
    is_leaf,
    sido_name,
    NULLIF(sigungu_name, '') AS sigungu_name,
    full_name,
    ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography AS location,
    coordinate_method,
    source_boundary_codes,
    boundary_base_date
FROM region_coordinate_stage;

ALTER TABLE region_coordinate ADD PRIMARY KEY (area_level, area_code);
CREATE INDEX region_coordinate_location_gix
    ON region_coordinate USING GIST (location);
```

화물 seed에는 시도와 시군구명을 함께 조인한다. 동명이 많은 `중구`, `서구`만으로 조인하면
잘못 연결될 수 있다.

```sql
UPDATE freight_offer f
SET origin_location = r.location
FROM region_coordinate r
WHERE r.area_level = 'SIGUNGU'
  AND r.sido_name = f.origin_sido
  AND r.sigungu_name = f.origin_sigungu;
```

현재 mock 데이터의 `수원시` 같은 상위 시는 `is_leaf=false` 행에 조인할 수 있다. 사용자가
구까지 입력했다면 `is_leaf=true` 행을 우선 사용한다. 기존 mock의 `전라북도`, `광주광역시`,
`전라남도`, 인천 `중구`·`서구`, 구 없는 `화성시`처럼 개편 전 명칭은 적재 전에 현재 명칭과
구역으로 정규화해야 한다. 하나의 옛 구역이 여러 신설 구로 나뉜 경우에는 임의 변환하지 말고
원 주소나 시나리오에 맞는 구를 지정한다.

기존 mock에서 구역이 갈라지지 않아 안전하게 일괄 변환할 수 있는 값은 다음과 같다.

| 기존 값 | 현재 값 |
|---|---|
| `전라북도` | `전북특별자치도` |
| `광주광역시` | `전남광주통합특별시` |
| `전라남도` | `전남광주통합특별시` |
| 세종 시군구 `세종시` | `세종특별자치시` |

인천 `중구`는 `제물포구`와 `영종구`, `서구`는 `서해구`와 `검단구`로 나뉘었으므로 주소나
mock 시나리오를 보고 수동 지정해야 한다. 기존 `화성시`는 상위 시 대표점으로는 그대로 쓸 수
있지만, 구 단위 화면에서는 화물 위치에 맞는 신설 구를 정해야 한다.

## 6. 재생성과 검증

공식 법정동 전체자료 ZIP과 SGIS ZIP에서 꺼낸 세 shapefile을 준비한 뒤 실행한다.

```bash
python3 -m venv .venv-admin-geo
.venv-admin-geo/bin/pip install pyshp shapely pyproj

.venv-admin-geo/bin/python scripts/generate-admin-area-coordinates.py \
  --codes-zip /path/to/법정동코드-전체자료.zip \
  --shapes-dir /path/to/extracted-boundaries \
  --output docs/korea-admin-area-coordinates.csv
```

`--shapes-dir`에는 다음 파일 묶음(`.shp`, `.shx`, `.dbf`, `.prj`, `.cpg`)이 있어야 한다.

- `bnd_sido_00_2025_2Q.*`
- `bnd_sigungu_00_2025_2Q.*`
- `bnd_dong_00_2025_2Q.*`

생성기는 시도 16개, 시군구 269개와 최하위 지역 256개, 코드 중복, 부모 코드, 대한민국 좌표
범위를 검증하고 하나라도 맞지 않으면 실패한다. 행정구역 개편 시에는 공식 코드를 다시 받고
최신 경계로 재생성해야 한다.

## 7. 출처와 사용 한계

- [행정표준코드관리시스템 법정동 코드](https://www.code.go.kr/stdcode/regCodeL.do) — 현재 명칭·코드와 폐지 여부
- [공공데이터포털 SGIS 행정구역 통계 및 경계](https://www.data.go.kr/data/15129688/fileData.do) — 2025-06-30 시도·시군구·행정동 SHP
- [공공데이터포털 2026-07-01 코드 변경 공지](https://www.data.go.kr/bbs/ntc/selectNotice.do?originId=NOTICE_0000000004801) — 인천 및 전남·광주 코드 전환
- [행정안전부 전남광주통합특별시 출범 안내](https://www.mois.go.kr/frt/bbs/type010/commonSelectBoardArticle.do?bbsId=BBSMSTR_000000000008&nttId=126845)
- [인천광역시 행정체제 개편 및 관할 동](https://www.incheon.go.kr/IC01070101)
- [행정안전부 화성시 4개 일반구 신설 코드 안내](https://www.mois.go.kr/frt/bbs/type001/commonSelectBoardArticle.do?bbsId=BBSMSTR_000000000052&nttId=122595)
- [화성특례시 구별 읍면동 목록](https://www.hscity.go.kr/www/partInfo/femaleFamily/Welfare8/Welfare8_1.jsp)

이 좌표는 구 단위 mock 추천과 1차 반경 필터에 적합한 **대표점**이지 주소 좌표나 도로
주행거리·ETA가 아니다. 실제 주소가 들어오면 주소를 지오코딩해 저장하고, 도로 거리와 시간은
길찾기 API로 별도 계산한다. 특히 면적이 넓거나 섬이 많은 시군구에서는 대표점 오차가 커질 수
있으므로 운영 서비스의 정밀 매칭에는 사용하지 않는다.
