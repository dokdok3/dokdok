# 🎨 Frontend

## 🛠 기술 스택

- Node v22
- Pnpm v9
- React v19
- TypeScript v5.9
- Vite v7
- Emotion v11
- TanStack Query v5
- Storybook v10
- Playwright v1.57

## 🚀 실행 방법

### 의존성 설치

```bash
pnpm install
```

### 개발 서버 실행

```bash
pnpm dev
```

개발 서버는 기본적으로 `http://localhost:5173`에서 실행됩니다.

### 프로덕션 빌드

```bash
pnpm build
```

### 빌드 미리보기

```bash
pnpm preview
```

### 린트

```bash
pnpm lint
```

### Storybook

```bash
pnpm storybook
pnpm build-storybook
pnpm test
```

`pnpm test`는 Chromium에서 Storybook interaction/a11y 테스트를 실행한다.

### Playwright E2E

최초 한 번 Chromium을 설치한다.

```bash
pnpm exec playwright install chromium
pnpm test:e2e
```
