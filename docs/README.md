# 카카오모빌리티 물류 해커톤 — 준비 문서 아카이브

최종 선정 주제: **화물 다단계 브로커리지 투명화** (AI 화물-차주 매칭 & 운임 적정성 체크)

## 최종 산출물

- [`prd.md`](./prd.md) — PRD (개요·사용자스토리·기술결정·API 계약·Out of Scope)
- [`feature-requirements.md`](./feature-requirements.md) — 페이지별 기능요구사항 (화주/기사 화면, API, 엣지케이스)
- [`llm-parsing.md`](./llm-parsing.md) — OpenAI Responses API 기반 화물 요청 파싱 프롬프트·JSON 계약·테스트 케이스
- [`matching-ranking.md`](./matching-ranking.md) — 기사 활동 지역·최소수락운임 기반 화물/기사 추천 랭킹 규칙
- [`mock-data.md`](./mock-data.md) — 기사 36명·화물 60건·구간 시세 24건의 데모용 seed 데이터
- [`curl-ranking.md`](./curl-ranking.md) — mock 랭킹 API 실행·curl 검증 방법
- [`openai-direct-curl.md`](./openai-direct-curl.md) — mock 기사·시세를 포함한 OpenAI Responses API 직접 curl 테스트
- [`team-timeline.md`](./team-timeline.md) — 본선 당일(09:00~18:00) 팀 배치·시간표
- [`discord-share.md`](./discord-share.md) — 팀 공유용 요약본
- [`wireframe.html`](./wireframe.html) — 화주/기사 페이지 와이어프레임 (브라우저로 열어서 확인, 탭 전환 가능)
- [`wireframe-notes.md`](./wireframe-notes.md) — 와이어프레임 회의 요약

## 주제 선정 과정

- [`judge-results.md`](./judge-results.md) — 5개 영역 15개 후보 블라인드 채점(1차)
- [`feasibility-check.md`](./feasibility-check.md) — 9시간 구현 가능성 검토
- [`final-scoring.md`](./final-scoring.md) — 최종 재채점 및 C13(다단계 브로커리지) 확정 근거
- [`mentor-qa-multi-tier-structure.md`](./mentor-qa-multi-tier-structure.md) — 멘토 Q&A: 다단계 브로커 구조가 유지되는 7가지 이유

## 영역별 리서치 (raw)

`research/` 폴더에는 5개 영역(택시/대리운전/주차/내비게이션/B2B물류배송)에 대한 딥 리서치 원본이 있다. 최종 선정 주제는 B2B물류배송 영역의 후보 1이며, 나머지는 초기 아이디어 발산 과정에서 검토했던 대안이다.

- [`research/taxi.md`](./research/taxi.md)
- [`research/daeri.md`](./research/daeri.md)
- [`research/navi.md`](./research/navi.md)
- [`research/parking.md`](./research/parking.md)
- [`research/b2b-logistics.md`](./research/b2b-logistics.md) — 최종 선정 주제의 근거 리서치
