# 랭킹 API 로컬 테스트

## 서버 실행

Java 25가 로컬에 있으면 다음처럼 실행한다.

```sh
cd backend
sh ./gradlew bootRun
```

8080을 다른 프로그램이 사용 중이면 포트만 바꾼다.

```sh
cd backend
sh ./gradlew bootRun --args='--server.port=18080'

# 새 터미널
API_BASE_URL=http://localhost:18080 sh scripts/test-ranking-api.sh
```

Java 25가 없으면 Docker로 실행한다.

```sh
docker run --rm -it -p 8080:8080 \
  -v "$PWD/backend:/app" -w /app \
  eclipse-temurin:25-jdk \
  bash -lc 'sh ./gradlew bootRun --no-daemon'
```

서버가 `http://localhost:8080`에서 준비된 뒤 새 터미널에서 아래 스크립트를 실행한다.

```sh
sh scripts/test-ranking-api.sh
```

원격 EC2는 8080을 외부 공개하지 않는다. SSH 터널을 열고 로컬 주소로 실행한다.

```sh
ssh -N -L 8080:localhost:8080 -i /path/to/key.pem ubuntu@43.202.205.211

# 새 터미널
sh scripts/test-ranking-api.sh
```

## 개별 curl

```sh
curl --fail-with-body --silent \
  'http://localhost:8080/api/offers?driverId=driver-01&page=0&size=20'
```

500건 seed에서 `driver-01`에는 총 109건의 적격 mock 화물이 있다. 첫 응답은 `content` 20개, `totalElements: 109`, `totalPages: 6`, `hasNext: true`를 반환하며, 두 번째 페이지의 첫 결과는 전체 `rank: 21`이다. 숨김 처리 후에는 적격 건수가 1건 감소한다.

`size`는 1~20만 가능하다. 예를 들어 `size=21`은 `400 Bad Request`가 정상이다.
