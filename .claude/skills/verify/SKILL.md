---
name: verify
description: AI 가 만든 변경분이나 외주사 PR 을 머지하기 전에, 변경분만 읽고 저장소 루트의 templates/approval-checklist.md 네 기준(테스트 통과 · 치명 이슈 0건 · 요청 범위 이탈 없음 · 컨벤션 준수)으로 승인 · 반려를 판정하고 이슈 목록을 파일:줄번호와 함께 보고한다. 코드는 고치지 않는다. "머지 전 검증", "PR 리뷰", "변경분 승인 판정", "외주 패치 검토" 같은 요청에 쓴다.
argument-hint: "[diff 범위 | patch 파일 경로] [요청 문장(선택)]"
---

# 머지 전 검증

검증 대상: $ARGUMENTS

- 인자가 비어 있으면 `git diff upstream/main...HEAD` 를 대상으로 삼는다.
- 절차는 저장소 루트의 `templates/verification-loop.md`, 판정 항목과 심각도 정의는 저장소 루트의 `templates/approval-checklist.md` 를 따른다. 경로는 이 Skill 폴더가 아니라 저장소 루트 기준이다.
- 이 Skill 은 리뷰 세션 역할만 한다. 코드를 고치기 시작하면 그 순간 "만든 쪽"이 된다.

## 1. 요청 범위 확인

- 무엇을 요청한 변경인지 한 문장으로 확인한다. 인자에 요청 문장이 있으면 그것을 쓴다.
- 요청 문장이 주어지지 않았으면 **판정하지 말고 먼저 묻는다.** 커밋 메시지나 diff 에서 요청을 짐작해 채우지 않는다.

## 2. 변경 파일 목록과 diff 읽기

- 목록을 먼저 본다.
  - diff 범위: `git diff --stat <범위>` (인자가 없으면 `git diff --stat upstream/main...HEAD`)
  - patch 파일(예: `vendor-prs/*.patch`): `git apply --stat <파일>`
- 그다음 파일마다 diff 를 읽는다(`git diff <범위> -- <파일>`). 판단에 필요하면 그 파일의 앞뒤 줄을 Read 로 읽는다.
- 변경 파일을 `modern/api` · `modern/web` · `characterization` · 그 밖(`legacy/`, `db/`, 설정 등)으로 나눠 둔다. 4단계에서 쓴다.

## 3. 체크리스트 대조

- 저장소 루트의 `templates/approval-checklist.md` 를 읽고, 항목마다 **충족 · 미충족 · 확인 필요** 중 하나로 표시한다.
- 컨벤션 항목은 저장소 루트의 `CLAUDE.md`(코딩 컨벤션 · 금지 사항)와 대조한다. `convention-check` Skill 이 있으면 그 결과를 쓴다.
- 체크리스트 항목을 이 파일에 옮겨 적지 않는다. 매번 원본 파일을 읽는다.

## 4. 테스트 실행

변경된 폴더의 테스트만 실행한다. 명령은 저장소 루트에서 해당 폴더로 들어가 실행한다.

| 변경된 폴더 | 명령 |
|---|---|
| `modern/api` | `cd modern/api && ./gradlew test` |
| `modern/web` | `cd modern/web && npm test` |
| 이관 코드 (레거시 동작을 `modern/` 으로 옮긴 변경) | `cd characterization && npm test` (레거시 대상)<br>`cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test` (새 API 대상) |

- 결과는 **통과 · 실패 수와 실패한 테스트 이름만** 남긴다. 전체 로그를 대화에 싣지 않는다.
  - 출력은 `tail` 이나 `grep` 으로 요약 줄만 본다(예: vitest 의 `Tests  N passed | M failed`).
  - Gradle 은 콘솔에 수를 바로 주지 않으므로 `modern/api/build/test-results/test/*.xml` 의 `tests` · `failures` · `errors` 속성을 합쳐 센다.
- 서버가 꺼져 있거나 의존성이 설치되지 않아 실행할 수 없으면 "실행하지 않음"과 그 이유를 적고, 사람에게 기동을 요청한다. characterization 은 레거시 대상이면 해당 compose 프로필이, 새 API 대상이면 `modern/api` 의 bootRun 이 떠 있어야 한다.
- characterization 의 테스트 코드나 `__snapshots__/` 가 diff 에 들어 있으면 그 자체를 이슈로 올린다(`CLAUDE.md` 완료 기준).

## 5. 승인 기준 판정

- 네 기준(테스트 통과 / 치명 이슈 0건 / 요청 범위 이탈 없음 / 컨벤션 준수)을 하나씩 **충족 · 미충족**으로 판정한다.
- 하나라도 미충족이면 반려다. 심각도 정의는 저장소 루트의 `templates/approval-checklist.md` 를 따른다.
- 이슈가 0건이면 경고 · 제안 수준까지 한 번 더 훑은 뒤 판정한다. 0건은 결과일 수도, 안 본 것일 수도 있다.

## 6. 출력 형식 (순서 고정)

```
판정: 승인 | 반려

검증 대상: <범위 또는 patch 파일>
요청 문장: <한 문장>

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 충족 / 미충족 | 실행 명령과 통과 · 실패 수, 실패한 테스트 이름 |
| 치명 이슈 0건 | 충족 / 미충족 | 치명 이슈 번호 |
| 요청 범위 이탈 없음 | 충족 / 미충족 | 요청 문장 vs 변경 파일 목록 |
| 컨벤션 준수 | 충족 / 미충족 | 위반 이슈 번호 |

이슈 목록
| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 치명 / 경고 / 제안 | `path/to/File.java:42` | … | … |

확인 필요
- <파일명> — <이유>
```

- 이슈 한 건은 심각도(치명 / 경고 / 제안) · `파일:줄번호` · 근거 · 수정 방향 네 항목을 모두 채운다.
- 이슈가 없으면 표 대신 "이슈 없음" 한 줄을 적는다.

## 7. 하지 말 것

- 코드를 수정하지 않는다. 보고만 한다.
- 근거 줄번호를 댈 수 없는 지적은 이슈로 올리지 않고 "확인 필요"로 남긴다.
- 테스트를 실행하지 않고 "통과"라고 쓰지 않는다.
- 요청 범위를 모르는 상태에서 "요청 범위 이탈 없음"을 충족으로 표시하지 않는다.
