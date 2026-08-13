# dokdok

## 프로젝트 구조

```text
dokdok/
├── backend/          Java 25 · Spring Boot 3.5 · Gradle
├── frontend/         Node 22 · pnpm 9 · React 19 · TypeScript 5.9 · Vite 7
├── docs/             기획·API·배포·데이터 문서
├── scripts/          mock 데이터 생성과 API 검증 스크립트
└── compose.yaml      로컬 PostgreSQL/PostGIS
```

```bash
cp .env.example .env
docker compose up -d postgres

cd backend
./gradlew bootRun

cd ../frontend
corepack enable
pnpm install --frozen-lockfile
pnpm dev
```

## 브랜치 전략

- main <- develop <- feature
- hotfix

## 기술 스택

- Backend
  - Java 25
  - Spring Boot 3.x
  - Gradle
- Frontend
  - Node v22
  - Pnpm v9
  - React v19
  - TypeScript v5.9
  - Vite v7
  - Emotion v11
  - TanStack Query v5
  - Storybook v10
  - Playwright v1.57
