---
name: convention-check
description: 변경된 코드(또는 지정한 경로)가 CLAUDE.md 의 코딩 컨벤션 · 금지 사항을 지키는지 예/아니오로 점검하고 위반 목록을 파일:줄번호와 함께 보고한다. "컨벤션 점검", "커밋 전 점검", "리뷰 전에 확인", "규칙 어긴 곳 찾아 줘" 같은 요청에 쓴다.
argument-hint: "[경로...] (생략하면 git 변경 파일)"
allowed-tools:
  - Read
  - Grep
  - Glob
  - Bash(git diff *)
  - Bash(git status *)
---

# 컨벤션 점검

- 코드를 직접 고치지 않는다.
- 근거 라인을 댈 수 없는 지적은 하지 않고 "확인 필요"로 남긴다.

## 1. 점검 대상 정하기

- 인자로 경로를 받았으면 그 경로(폴더면 그 아래 `.java` · `.ts` · `.tsx`)만 본다.
- 인자가 없으면 `git status --porcelain` 과 `git diff --name-only HEAD` 로 변경 파일을 모은다.
  아직 add 하지 않은 새 파일(`??`)도 포함하고, 삭제된 파일은 뺀다.
- 대상이 없으면 "점검할 변경 파일 없음"이라고만 답하고 끝낸다.

## 2. 점검 항목 (예 = 준수)

| # | 대상 | 판정 기준 |
|---|---|---|
| 1 | `modern/api` 의 `*Controller.java` | `*Repository` 타입 필드 · 생성자 파라미터가 없고, 메서드 안에 `try` 가 없다 |
| 2 | `.java` | 모든 `catch` 블록 안에 `throw` 가 있다(빈 `catch` · 로그만 남기는 `catch` 없음) |
| 3 | `.java` | 인자 없는 `now()`(`LocalDateTime.now()`, `Instant.now()` 등)가 없다 |
| 4 | `.java` | `System.out` · `System.err` · `printStackTrace()` 가 없다 |
| 5 | `.java` | 와일드카드 import(`import ….*;`)가 없다 |
| 6 | `modern/web` 의 `.ts` · `.tsx` | `any` 타입 · `as unknown as` · `@ts-ignore` · `dangerouslySetInnerHTML` · `console.log` 가 없다 |
| 7 | `modern/web` 의 `.ts` · `.tsx` | `src/api/client.ts` 밖에서 `fetch(` 를 호출하지 않는다 |
| 8 | `modern/web` 의 `.ts` · `.tsx` | `eslint-disable` 주석마다 같은 줄에 사유가 있다 |

- Grep 으로 후보 줄을 찾은 뒤 Read 로 앞뒤를 읽어 확정한다. 주석 · 문자열 안의 일치는 위반이 아니다.
- 인자 없이 돌 때는 변경 파일 전체를 보되, `git diff` 에 잡힌 줄의 위반을 먼저 적는다.
- 판정에 해당 파일이 없는 항목은 "해당 없음"으로 둔다.

## 3. 출력 형식 (순서 고정)

### 판정 요약

```
점검 대상: N개 파일 (인자 경로 | git 변경 파일)
1 컨트롤러 Repository · try  예
2 catch 다시 던지기          아니오 (2건)
…
8 eslint-disable 사유         해당 없음
```

### 위반 목록

| 파일:줄번호 | 어긴 규칙 | 수정 방향 |
|---|---|---|
| `modern/api/.../FooService.java:42` | #3 인자 없는 `now()` | `Clock` 빈을 주입받아 `LocalDateTime.now(clock)` |

- 위반이 없으면 표 대신 "위반 없음" 한 줄을 적는다.
- 근거 줄을 특정하지 못한 의심 사항은 표 아래 "확인 필요" 목록에 파일명과 이유만 적는다.
