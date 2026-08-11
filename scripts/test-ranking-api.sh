#!/usr/bin/env sh
set -eu

API_BASE_URL="${API_BASE_URL:-http://localhost:8080}"

request() {
  printf '\n%s\n' "$1"
  shift
  curl --fail-with-body --silent --show-error "$@"
  printf '\n'
}

request "1) driver-01 활동 지역 조회" \
  "$API_BASE_URL/api/drivers/driver-01/activity-region"

request "2) 기본 활동 지역의 추천 화물 첫 페이지: 최대 20건" \
  "$API_BASE_URL/api/offers?driverId=driver-01&page=0&size=20"

request "3) 기본 활동 지역의 추천 화물 두 번째 페이지" \
  "$API_BASE_URL/api/offers?driverId=driver-01&page=1&size=20"

request "4) 화주 요청으로 최종 기사 1명 매칭" \
  --request POST \
  --header 'Content-Type: application/json' \
  --data '{"cargoType":"REFRIGERATED","originSido":"서울특별시","originSigungu":"송파구","destinationSido":"부산광역시","destinationSigungu":"강서구","offeredFareKrw":500000}' \
  "$API_BASE_URL/api/match"

request "5) 활동 지역을 대전 유성 → 경기 수원으로 수정" \
  --request PUT \
  --header 'Content-Type: application/json' \
  --data '{"originSido":"대전광역시","originSigungu":"유성구","destinationSido":"경기도","destinationSigungu":"수원시"}' \
  "$API_BASE_URL/api/drivers/driver-01/activity-region"

request "6) 변경된 활동 지역의 추천 화물 첫 페이지" \
  "$API_BASE_URL/api/offers?driverId=driver-01&page=0&size=20"

request "7) 첫 화물 수락" \
  --request POST \
  "$API_BASE_URL/api/offers/freight-01/accept?driverId=driver-01"

request "8) 두 번째 화물 숨기기" \
  --request POST \
  "$API_BASE_URL/api/offers/freight-02/hide?driverId=driver-01"

request "9) 숨김 처리 후 첫 페이지 재조회" \
  "$API_BASE_URL/api/offers?driverId=driver-01&page=0&size=20"
