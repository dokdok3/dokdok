# OpenAI 직접 curl 테스트 (mock 데이터 포함)

이 테스트는 OpenAI Responses API에 화물 원문, mock 기사 3명, 운임 시세를 한 번에 보내고 아래 JSON을 받는다.

- 비정형 화물 요청 파싱
- 시세 대비 운임 상태
- 가장 적합한 mock 기사 1명과 추천 이유

실서비스의 기사 20건 랭킹은 [`matching-ranking.md`](./matching-ranking.md)와 백엔드 `GET /api/offers`가 처리한다. 이 스크립트는 LLM 프롬프트 데모·검증용이며 랭킹 로직의 대체물이 아니다.

## 실행

OpenAI 프로젝트 API 키를 터미널 환경변수에만 넣는다. 키를 코드·`.env` 추적 파일·채팅에 넣지 않는다.

```sh
export OPENAI_API_KEY='발급받은_프로젝트_API_키'
OPENAI_MODEL=gpt-5.6-luna sh scripts/test-openai-mock-demo.sh
```

다른 화물 원문으로 바꾸려면 첫 번째 인자로 전달한다.

```sh
OPENAI_MODEL=gpt-5.6-luna \
sh scripts/test-openai-mock-demo.sh \
  '내일 서울 송파구에서 부산 강서구로 냉장식품 5톤 보내요. 예산 50만원입니다.'
```

기본 mock에는 서울 송파→부산 강서 냉장 화물의 평균 운임 72만원과, 해당 조건에 맞는 `driver-01`이 들어 있다. 따라서 기본 예시는 `LOW` 운임 경고와 `driver-01` 추천을 확인하는 목적이다.

Responses API는 `instructions`와 `input`을 받아 호출하며, Structured Outputs의 JSON Schema로 응답 모양을 고정할 수 있다. [OpenAI 공식 Structured Outputs 문서](https://developers.openai.com/api/docs/guides/structured-outputs)
