# EC2 Docker/PostGIS 배포 준비

> 기준: Ubuntu 24.04 LTS, `t3.small`, Elastic IP, 단일 EC2, ELB 없음  
> 목적: 해커톤 데모 서버. Spring Boot + PostgreSQL/PostGIS를 우선 실행하고 Redis는 필요할 때만 켠다.  
> 배포: GitHub Actions CI → GHCR 이미지 발행 → EC2 SSH 재기동·health check·자동 롤백

## 1. 결론

EC2 호스트에는 Java, PostgreSQL, Redis를 각각 직접 설치하지 않는다. **Docker Engine과 Docker
Compose 플러그인만 설치**하고 애플리케이션과 데이터베이스를 컨테이너로 실행한다.

최소 구성은 다음과 같다.

| 구성 | 필수 여부 | 역할 |
|---|---|---|
| Docker Engine + Compose | 필수 | 실행 환경 |
| Spring Boot 컨테이너 | 필수 | API와 OpenAI 호출 |
| PostgreSQL + PostGIS 컨테이너 | 필수 | 화물·기사 데이터와 거리 계산 |
| Nginx 컨테이너 | 조건부 | 프론트 정적 파일 또는 API reverse proxy |
| Redis 컨테이너 | 선택 | 캐시·세션·중복 작업 방지가 실제로 필요할 때만 사용 |

프론트엔드를 Vercel 등에 배포한다면 EC2에는 Nginx 정적 파일 컨테이너가 필요 없다. 이 경우에도
HTTPS API를 공개하려면 Nginx/Caddy 같은 reverse proxy 또는 별도 HTTPS 종단이 필요하다.

AWS 공식 사양상 `t3.small`은 2 vCPU, 2 GiB 메모리의 버스터블 인스턴스다. 해커톤 트래픽에는
쓸 수 있지만 Spring Boot와 PostGIS를 함께 실행하면 메모리가 빠듯하므로 EC2에서 Gradle·Vite
빌드까지 수행하지 않는다. 로컬이나 GitHub Actions에서 이미지를 빌드하고 EC2는 이미지만 받아
실행한다.

## 2. 보안 그룹

EC2 보안 그룹 인바운드는 최소한으로 유지한다.

| 포트 | 소스 | 용도 |
|---:|---|---|
| 22/TCP | 팀의 현재 공인 IP `/32` | SSH |
| 80/TCP | `0.0.0.0/0` | HTTP 및 HTTPS 리다이렉트 |
| 443/TCP | `0.0.0.0/0` | 공개 HTTPS |

- `5432`(PostgreSQL), `6379`(Redis)는 열지 않는다.
- Nginx를 사용한다면 `8080`(Spring Boot)도 열지 않는다.
- 임시 API 테스트로 `8080`이 필요해도 전체 공개 대신 SSH 터널을 사용한다.
- Docker가 공개한 포트는 UFW 규칙을 우회할 수 있으므로 보안 그룹과 Compose의 `ports`를 둘 다
  확인한다.

SSH 터널 예시:

```bash
ssh -i /path/to/hackathon.pem -L 8080:127.0.0.1:8080 ubuntu@EC2_ELASTIC_IP
```

이후 로컬에서 `http://localhost:8080`으로 접근한다.

## 3. Docker 설치

아래는 Docker 공식 APT 저장소를 이용하는 Ubuntu 절차다. AMI가 Amazon Linux라면 이 명령을
그대로 실행하지 말고 Docker의 해당 배포판 절차를 사용한다.

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

sudo tee /etc/apt/sources.list.d/docker.sources >/dev/null <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF

sudo apt-get update
sudo apt-get install -y \
  docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo docker run --rm hello-world
sudo docker compose version
```

매번 `sudo`를 붙이지 않으려면 다음을 실행하고 SSH에 다시 로그인한다. `docker` 그룹은 사실상
root 권한을 가질 수 있으므로 서버 사용자에게만 부여한다.

```bash
sudo usermod -aG docker "$USER"
exit
```

재접속 후 확인한다.

```bash
docker version
docker compose version
```

## 4. 스왑 2 GiB 추가

먼저 기존 스왑을 확인한다.

```bash
free -h
swapon --show
```

스왑이 없다면 한 번만 생성한다. 스왑은 메모리 부족 시 프로세스가 즉시 종료되는 것을 완화하지만
RAM보다 훨씬 느리므로 지속적으로 사용된다면 `t3.medium` 이상으로 올려야 한다.

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h
```

이미 `/etc/fstab`에 `/swapfile`이 있으면 마지막 `echo` 명령은 다시 실행하지 않는다.

## 5. 서버 디렉터리와 비밀값

```bash
sudo install -d -o "$USER" -g "$USER" /opt/hackathon
cd /opt/hackathon
touch .env
chmod 600 .env
```

`.env` 예시다. 실제 값은 Git에 커밋하지 않는다.

```dotenv
POSTGRES_DB=hackathon
POSTGRES_USER=hackathon
POSTGRES_PASSWORD=충분히_긴_무작위_비밀번호
BACKEND_IMAGE=ghcr.io/OWNER/REPOSITORY/backend:COMMIT_SHA
OPENAI_API_KEY=서버에서만_사용하는_API_KEY
```

비밀번호는 로컬에서 `openssl rand -base64 32` 등으로 생성한다. OpenAI API 키는 프론트 빌드
환경변수에 넣거나 `VITE_` 접두사로 만들지 않는다.

## 6. 권장 Compose

`/opt/hackathon/compose.yaml` 예시다. PostGIS 프로젝트가 신규 사용자에게 권장하는 안정 태그 중
PostgreSQL 17 계열인 `postgis/postgis:17-3.5`를 사용한다. `latest` 대신 명시적 태그를 고정한다.

```yaml
services:
  postgres:
    image: postgis/postgis:17-3.5
    restart: unless-stopped
    environment:
      POSTGRES_DB: ${POSTGRES_DB}
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    volumes:
      - postgres-data:/var/lib/postgresql/data
    shm_size: 128mb
    mem_limit: 600m
    command:
      - postgres
      - -c
      - max_connections=30
      - -c
      - shared_buffers=128MB
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $${POSTGRES_USER} -d $${POSTGRES_DB}"]
      interval: 5s
      timeout: 3s
      retries: 20
      start_period: 10s
    networks: [internal]

  backend:
    image: ${BACKEND_IMAGE}
    restart: unless-stopped
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB}
      SPRING_DATASOURCE_USERNAME: ${POSTGRES_USER}
      SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD}
      OPENAI_API_KEY: ${OPENAI_API_KEY}
      JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=70 -XX:InitialRAMPercentage=25
    depends_on:
      postgres:
        condition: service_healthy
    mem_limit: 850m
    expose: ["8080"]
    networks: [internal]

  nginx:
    image: nginx:1.29-alpine
    restart: unless-stopped
    depends_on: [backend]
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx.conf:/etc/nginx/conf.d/default.conf:ro
      - ./certs:/etc/nginx/certs:ro
    mem_limit: 64m
    networks: [internal]

  redis:
    image: redis:8-alpine
    profiles: ["redis"]
    restart: unless-stopped
    command: redis-server --appendonly yes --maxmemory 96mb --maxmemory-policy allkeys-lru
    volumes:
      - redis-data:/data
    mem_limit: 128m
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 10
    networks: [internal]

networks:
  internal:

volumes:
  postgres-data:
  redis-data:
```

중요한 점은 PostgreSQL과 Redis에 `ports`를 작성하지 않는 것이다. 두 서비스는 Compose 내부
네트워크에서만 접근한다. 외부 DB 도구가 필요하면 SSH 터널과 `127.0.0.1` 바인딩을 임시로
사용한다.

Redis 없이 실행:

```bash
docker compose config
docker compose pull
docker compose up -d
```

Redis가 실제로 필요해진 뒤 실행:

```bash
docker compose --profile redis up -d
```

Redis를 활성화하면 백엔드에도 다음 환경변수를 추가한다.

```yaml
SPRING_DATA_REDIS_HOST: redis
SPRING_DATA_REDIS_PORT: 6379
```

## 7. Nginx 최소 설정

아직 도메인과 인증서가 없다면 최초 연결 확인용으로 `/opt/hackathon/nginx.conf`를 다음처럼
둘 수 있다. 이 상태는 HTTP이므로 공개 데모 전에는 HTTPS를 붙인다.

```nginx
server {
    listen 80;
    server_name _;

    location /api/ {
        proxy_pass http://backend:8080/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /actuator/health {
        proxy_pass http://backend:8080/actuator/health;
    }
}
```

Compose에서 아직 인증서가 없다면 `443:443`과 `./certs` 마운트는 잠시 제거한다. HTTPS는
도메인의 A 레코드를 Elastic IP로 연결한 후 인증서를 발급해 적용한다. 프론트와 API 도메인이
다르면 Spring CORS 허용 출처를 정확한 프론트 도메인으로 제한한다.

## 8. 배포와 확인

```bash
cd /opt/hackathon
docker compose config
docker compose pull
docker compose up -d
docker compose ps
docker compose logs --tail=100 postgres backend nginx
curl -fsS http://localhost/actuator/health
```

PostGIS 확인:

```bash
docker compose exec postgres \
  sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "SELECT PostGIS_Full_Version();"'
```

배포 갱신:

```bash
cd /opt/hackathon
docker compose pull backend
docker compose up -d --no-deps backend
docker compose logs --tail=100 backend
```

장애 확인 순서:

```bash
free -h
df -h
docker stats --no-stream
docker compose ps
docker compose logs --tail=200 backend postgres
```

컨테이너가 `OOMKilled`라면 다음으로 확인한다.

```bash
docker inspect --format '{{.State.OOMKilled}}' "$(docker compose ps -q backend)"
```

## 9. GitHub Actions CI/CD 구조

권장 흐름은 다음과 같다.

```text
Pull Request
  ├─ backend: Java 25 + Gradle test
  └─ frontend: Node 22 + pnpm 9 lint/build

main push
  ├─ 위 CI 재실행
  ├─ backend Docker image build
  ├─ ghcr.io/dokdok3/dokdok-backend:<commit-sha> push
  └─ production 승인 → EC2 SSH → 해당 SHA pull/up → health check
```

프론트는 현재 문서의 기본안대로 Vercel 자동 배포를 사용해도 된다. 이 경우 GitHub Actions는
프론트 CI까지만 담당하고, EC2 CD는 백엔드만 담당한다. 프론트까지 EC2에 둘 경우 아래
`Dockerfile`/workflow를 같은 방식으로 한 벌 더 추가한다.

이미지에는 `latest`만 사용하지 않고 Git commit SHA를 태그로 사용한다. 어떤 코드가 배포됐는지
확인하고 이전 SHA로 되돌릴 수 있기 때문이다.

### GitHub 설정

저장소의 `Settings → Environments`에서 `production` 환경을 만들고 가능하면 승인자를 지정한다.
다음 값은 **production environment secrets**로 저장한다.

| Secret | 값 |
|---|---|
| `EC2_HOST` | Elastic IP 또는 배포 도메인 |
| `EC2_USER` | Ubuntu AMI라면 보통 `ubuntu` |
| `EC2_SSH_PRIVATE_KEY` | 배포 전용 SSH 개인키 전문 |
| `EC2_KNOWN_HOSTS` | 검증한 EC2 SSH host key 한 줄 |

`POSTGRES_PASSWORD`와 `OPENAI_API_KEY`는 GitHub Actions가 알 필요가 없다. EC2의 권한 제한된
`/opt/hackathon/.env`에만 둔다. GitHub Actions에는 테스트에 꼭 필요한 비밀만 추가한다.

`EC2_KNOWN_HOSTS`는 신뢰할 수 있는 네트워크에서 다음으로 만들고, EC2 콘솔 등 별도 경로로
fingerprint를 확인한 뒤 등록한다. CD에서 `StrictHostKeyChecking=no`를 사용하지 않는다.

```bash
ssh-keyscan -H EC2_ELASTIC_IP
```

### GHCR 접근

워크플로는 기본 `GITHUB_TOKEN`에 `packages: write`만 부여해 GHCR에 이미지를 올린다. 가장
단순한 해커톤 구성은 backend package를 public으로 전환해 EC2가 인증 없이 pull하게 하는
것이다.

이미지를 private으로 유지한다면 EC2 전용 GitHub 토큰에 `read:packages` 최소 권한만 주고
EC2에서 한 번 로그인한다. 개인 토큰을 `.env`나 Compose 파일에 쓰지 않는다.

```bash
read -rsp 'GHCR token: ' GHCR_TOKEN
echo
printf '%s' "$GHCR_TOKEN" | docker login ghcr.io -u GITHUB_USER --password-stdin
unset GHCR_TOKEN
```

명령 기록이나 터미널 공유 화면에 실제 토큰이 남지 않도록 직접 입력하거나 안전한 secret
전달 방식을 사용한다.

## 10. CI/CD 활성화 전 현재 저장소 점검

2026-08-13 현재 workflow를 바로 활성화할 수 없는 항목이 있다.

| 현재 상태 | 목표/필수 작업 |
|---|---|
| `backend/build.gradle.kts`: Spring Boot `4.0.0` | 팀 합의가 3.x라면 사용할 3.x 버전으로 통일 |
| MySQL runtime driver | `org.postgresql:postgresql` 드라이버로 교체 |
| DataSource/JPA 자동설정 제외 | PostgreSQL 연결 구현 시 제외 설정 제거 |
| Actuator 의존성 없음 | `spring-boot-starter-actuator` 추가 후 health endpoint 노출 |
| backend/frontend Dockerfile 없음 | 아래 예시를 기준으로 각각 추가 |
| 프론트 테스트 script 없음 | 최소 `lint`와 `build`를 CI gate로 사용하거나 Vitest script 추가 |
| `backend/gradlew` 실행 비트 없음 | `git update-index --chmod=+x backend/gradlew`로 저장소에 반영 |
| frontend build 실패 | `src/stories/Button.tsx`, `Header.tsx`의 사용하지 않는 `React` import 제거 |

이 문서의 workflow는 위 항목을 끝낸 후 `.github/workflows/ci-cd.yml`로 활성화한다. 기존
`issue-label.yml`, `pr-label.yml`, `pr-discord.yml`과는 별개이며 삭제하지 않는다.

2026-08-13 로컬 점검 결과 `bash backend/gradlew test --no-daemon`과 frontend lint는
통과했다. frontend build는 위 두 Storybook 파일의 TypeScript `TS6133` 오류로 실패했다. 즉,
현재는 문서 예제만 추가한 상태이며 실패하는 workflow를 저장소에 활성화하지 않았다.

## 11. 백엔드 Dockerfile

현재 저장소에 `backend/Dockerfile`이 생긴 뒤 CD를 활성화한다. Java 25로 Gradle 빌드 후
JRE 이미지에서 실행하는 예시는 다음과 같다.

```dockerfile
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew
RUN ./gradlew dependencies --no-daemon
COPY src ./src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

`.dockerignore`도 둔다.

```text
.gradle
build
.env
```

`build.gradle.kts`에 여러 실행 JAR이 생기면 `COPY *.jar`가 모호해질 수 있으므로 `bootJar`의
출력 이름을 고정한다. 현재 백엔드는 PostgreSQL 연결 전환과 Actuator health endpoint 추가가
끝난 다음 배포 이미지로 사용한다.

## 12. CI/CD workflow 예시

`.github/workflows/ci-cd.yml` 예시다. 현재 저장소에는 기존 PR 알림·라벨 workflow만 있으므로
파일명이 충돌하지 않는다.

```yaml
name: CI and Deploy

on:
  pull_request:
    branches: [main]
  push:
    branches: [main]
  workflow_dispatch:

concurrency:
  group: production-${{ github.ref }}
  cancel-in-progress: false

permissions:
  contents: read

env:
  REGISTRY: ghcr.io
  BACKEND_IMAGE: ghcr.io/dokdok3/dokdok-backend

jobs:
  backend-ci:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: backend
    steps:
      - uses: actions/checkout@v6
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "25"
      - uses: gradle/actions/setup-gradle@017a9effdb900e5b5b2fddfb590a105619dca3c3
      - run: chmod +x gradlew
      - run: ./gradlew test --no-daemon

  frontend-ci:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: frontend
    steps:
      - uses: actions/checkout@v6
      - uses: actions/setup-node@v6
        with:
          node-version: "22"
      - run: corepack enable
      - run: corepack prepare pnpm@9 --activate
      - run: pnpm install --frozen-lockfile
      - run: pnpm lint
      - run: pnpm build

  publish-backend:
    if: github.ref == 'refs/heads/main' && github.event_name != 'pull_request'
    needs: [backend-ci, frontend-ci]
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write
    steps:
      - uses: actions/checkout@v6
      - uses: docker/setup-buildx-action@v3
      - uses: docker/login-action@v3
        with:
          registry: ${{ env.REGISTRY }}
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - uses: docker/build-push-action@v6
        with:
          context: ./backend
          file: ./backend/Dockerfile
          push: true
          tags: |
            ${{ env.BACKEND_IMAGE }}:${{ github.sha }}
          cache-from: type=gha
          cache-to: type=gha,mode=max

  deploy:
    if: github.ref == 'refs/heads/main' && github.event_name != 'pull_request'
    needs: publish-backend
    runs-on: ubuntu-latest
    environment: production
    timeout-minutes: 10
    steps:
      - name: Configure SSH
        env:
          SSH_PRIVATE_KEY: ${{ secrets.EC2_SSH_PRIVATE_KEY }}
          SSH_KNOWN_HOSTS: ${{ secrets.EC2_KNOWN_HOSTS }}
        run: |
          install -m 700 -d ~/.ssh
          printf '%s\n' "$SSH_PRIVATE_KEY" > ~/.ssh/ec2_deploy
          chmod 600 ~/.ssh/ec2_deploy
          printf '%s\n' "$SSH_KNOWN_HOSTS" > ~/.ssh/known_hosts
          chmod 600 ~/.ssh/known_hosts

      - name: Deploy commit image
        env:
          EC2_HOST: ${{ secrets.EC2_HOST }}
          EC2_USER: ${{ secrets.EC2_USER }}
        run: |
          ssh -i ~/.ssh/ec2_deploy "$EC2_USER@$EC2_HOST" \
            "/opt/hackathon/deploy.sh '${{ github.sha }}'"
```

운영 workflow에서는 서드파티 action도 버전 태그 대신 검토한 commit SHA로 고정하는 것이 좋다.
위 Gradle action은 GitHub 공식 Gradle 예제에 나온 SHA로 고정했으며, Docker action도 활성화 전에
각 릴리스의 SHA로 바꾼다.

## 13. EC2 배포 스크립트와 롤백

Compose의 백엔드 이미지를 commit SHA로 선택하도록 `/opt/hackathon/compose.yaml`을 바꾼다.

```yaml
backend:
  image: ghcr.io/dokdok3/dokdok-backend:${IMAGE_TAG}
```

EC2에 `/opt/hackathon/deploy.sh`를 만들고 실행 권한을 준다. 이 스크립트는 SHA 형식을 검증하고,
Nginx가 새 backend 주소를 다시 해석하도록 재시작한 뒤 health endpoint가 실패하면 직전 SHA로
되돌린다. 단일 EC2·단일 backend라 교체 중 수초의 중단은 발생할 수 있다.

```bash
#!/usr/bin/env bash
set -Eeuo pipefail

APP_DIR=/opt/hackathon
NEW_TAG="${1:?commit SHA is required}"
RELEASE_FILE="$APP_DIR/.env.release"
PREVIOUS_TAG=""

if [[ ! "$NEW_TAG" =~ ^[0-9a-f]{40}$ ]]; then
  echo "invalid commit SHA" >&2
  exit 2
fi

cd "$APP_DIR"

if [[ -f "$RELEASE_FILE" ]]; then
  PREVIOUS_TAG="$(sed -n 's/^IMAGE_TAG=//p' "$RELEASE_FILE")"
fi

printf 'IMAGE_TAG=%s\n' "$NEW_TAG" > "$RELEASE_FILE.next"
mv "$RELEASE_FILE.next" "$RELEASE_FILE"

compose() {
  docker compose --env-file .env --env-file .env.release "$@"
}

rollback() {
  if [[ "$PREVIOUS_TAG" =~ ^[0-9a-f]{40}$ ]]; then
    printf 'IMAGE_TAG=%s\n' "$PREVIOUS_TAG" > "$RELEASE_FILE"
    compose up -d --no-deps backend
    compose restart nginx
  else
    rm -f "$RELEASE_FILE"
  fi
}
trap rollback ERR

compose pull backend
compose up -d --no-deps backend
compose restart nginx

for attempt in {1..30}; do
  if curl -fsS http://127.0.0.1/actuator/health >/dev/null; then
    trap - ERR
    docker image prune -f >/dev/null 2>&1 || true
    echo "deployed $NEW_TAG"
    exit 0
  fi
  sleep 2
done

echo "health check failed" >&2
exit 1
```

```bash
sudo chown root:docker /opt/hackathon/deploy.sh
sudo chmod 750 /opt/hackathon/deploy.sh
```

최초 배포 전에 `.env.release`를 만든다.

```bash
cd /opt/hackathon
printf 'IMAGE_TAG=%s\n' INITIAL_COMMIT_SHA > .env.release
chmod 600 .env .env.release
```

수동 롤백은 성공했던 이전 commit SHA로 같은 스크립트를 실행한다.

```bash
/opt/hackathon/deploy.sh PREVIOUS_40_CHARACTER_COMMIT_SHA
```

### 프론트까지 EC2에 배포할 경우

`frontend/Dockerfile`에서 `pnpm build` 후 결과물 `dist/`를 Nginx 이미지로 복사하고,
`ghcr.io/dokdok3/dokdok-frontend:<commit-sha>`를 발행한다. workflow의 publish job을 matrix로
바꾸고 EC2 스크립트에서 `backend web` 두 서비스를 함께 pull/up한다.

프론트는 빌드 시 API URL을 `/api` 같은 동일 출처 상대 경로로 고정해야 같은 이미지를 환경마다
재사용할 수 있다. OpenAI 키나 DB 비밀번호를 `VITE_` 변수로 전달해서는 안 된다.

## 14. 데이터 보존과 백업

`docker compose down`은 컨테이너를 내리지만 named volume은 유지한다. `docker compose down -v`는
DB 볼륨까지 삭제하므로 사용하지 않는다.

간단한 논리 백업:

```bash
cd /opt/hackathon
mkdir -p backups
docker compose exec -T postgres \
  sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' \
  > "backups/hackathon-$(date +%Y%m%d-%H%M%S).dump"
```

해커톤 직전에는 EBS 스냅샷 또는 위 dump 파일을 EC2 외부에도 한 번 보관한다. named volume은
EC2 인스턴스나 EBS 볼륨 자체가 사라지면 함께 잃을 수 있다.

## 15. 본선 전 체크리스트

- [ ] Elastic IP가 EC2에 연결되어 있다.
- [ ] SSH 22번은 팀 공인 IP `/32`에서만 접근된다.
- [ ] 5432, 6379, 8080이 인터넷에 공개되지 않았다.
- [ ] `docker compose config`가 비밀값 누락 없이 성공한다.
- [ ] PostGIS 버전 쿼리가 성공한다.
- [ ] 백엔드 health check가 성공한다.
- [ ] 재부팅 후 컨테이너가 `restart: unless-stopped`로 복구된다.
- [ ] OpenAI 키가 프론트 번들이나 Git에 포함되지 않았다.
- [ ] DB dump 또는 EBS 스냅샷이 있다.
- [ ] `free -h`, `docker stats`에서 메모리 여유를 확인했다.
- [ ] Redis 없이 먼저 전체 흐름을 통과시켰다.
- [ ] PR에서 backend test와 frontend lint/build가 통과한다.
- [ ] `main` 배포가 GHCR commit SHA 이미지로 실행된다.
- [ ] 실패한 health check가 이전 SHA로 롤백된다.
- [ ] GitHub `production` 환경의 secret과 배포 권한이 제한되어 있다.
- [ ] Spring Boot 3.x/4.x 선택, PostgreSQL 드라이버, Actuator 설정이 저장소와 문서에서 일치한다.

## 16. 참고 자료

- [Docker 공식 Ubuntu 설치 문서](https://docs.docker.com/engine/install/ubuntu/)
- [Docker Compose 플러그인 설치 문서](https://docs.docker.com/compose/install/linux/)
- [AWS EC2 범용 인스턴스 사양](https://docs.aws.amazon.com/ec2/latest/instancetypes/gp.html)
- [AWS EC2 보안 그룹 사용 사례](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/security-group-rules-reference.html)
- [PostGIS Docker 이미지](https://hub.docker.com/r/postgis/postgis)
- [GitHub Actions Java/Gradle 빌드](https://docs.github.com/actions/tutorials/build-and-test-code/java-with-gradle)
- [GitHub Actions 컨테이너 이미지 발행](https://docs.github.com/actions/tutorials/publish-packages/publish-docker-images)
- [GitHub Actions deployment environment](https://docs.github.com/actions/reference/workflows-and-actions/deployments-and-environments)
- [GitHub Actions secrets](https://docs.github.com/actions/concepts/security/secrets)
- [`postgres-distance-ranking.md`](./postgres-distance-ranking.md)
