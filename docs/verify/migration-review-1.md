판정: 반려

검증 대상: `upstream/main...HEAD` (커밋 3개: 91932d3 · 6f5b83a · 8f213de, 변경 파일 23개)
요청 문장: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관.

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 충족 | `cd modern/api && ./gradlew cleanTest test` → 46개 중 통과 46 · 실패 0 · 오류 0 · skipped 0. `cd characterization && npm test`(레거시 8081) → 30 통과 · 0 실패. `TARGET_BASE_URL=http://localhost:8080 npm test`(새 API) → 29 통과 · 0 실패 · 1 skipped(아래 확인 필요 1). 기존 테스트를 지우거나 skip 으로 바꾼 곳 없음 |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음(SQL 은 Criteria 바인딩, 비밀값 · 데이터 삭제 · 덮어쓰기 코드 없음) |
| 요청 범위 이탈 없음 | 미충족 | 이슈 1, 2. 요청은 엔드포인트 1개(`GET /api/items/search`) 이관인데 변경 23개 중 이관 코드(main 7 · test 4)가 아닌 파일이 12개(문서 · 설정 · Skill 10, characterization 2)다 |
| 컨벤션 준수 | 충족 | 위반 없음. 경고 · 제안 수준(이슈 3, 4)만 있다 |

## 변경 파일 분류

| 구분 | 파일 |
|---|---|
| modern/api (main 7) | `ItemController` · `ItemRepository` · `ItemService`(수정), `ItemSearchCondition` · `ItemSearchResponse` · `ItemSearchRow` · `ItemSpecifications`(신규) |
| modern/api (test 4) | `ItemControllerTest` · `ItemRepositoryTest` · `ItemServiceTest` · `ItemSearchConditionTest` |
| characterization | `tests/item-bank.test.js` · `__snapshots__/item-bank.test.js.snap` (둘 다 신규) |
| 그 밖 | `CLAUDE.md`, `.claude/skills/convention-check/SKILL.md`, `.claude/skills/document-module/SKILL.md`, `docs/item-bank/*`(4), `docs/assignment/*`(3) |

modern/web · legacy/ · db/ · 의존성(`build.gradle`) · `application.yml` 변경은 없다.

## 이슈 목록

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 | `docs/assignment/ARCHITECTURE.md:1` (`BUSINESS-RULES.md:1`, `ERD.md:1`) | 이번 요청(item-bank 검색 1개 이관)과 무관한 assignment 모듈 문서 3개가 범위에 들어 있다. `CLAUDE.md:1`, `.claude/skills/convention-check/SKILL.md:1`, `.claude/skills/document-module/SKILL.md:1` 도 이관 코드가 아니다 | 별도 PR 로 분리하거나, 이번 변경에 포함하기로 사람이 확정한다. `upstream/main` 이 뒤처져 있어 이전 커밋(6f5b83a 등)까지 범위에 잡혔다면 비교 기준을 맞춰 다시 검증한다 |
| 2 | 경고 | `characterization/tests/item-bank.test.js:1`, `characterization/__snapshots__/item-bank.test.js.snap:1` | 완료 기준(`CLAUDE.md` §5): characterization 의 테스트 · 스냅샷이 diff 에 들어 있으면 이슈로 올린다. 두 파일은 신규 추가이며 커밋 91932d3 한 번뿐이고, 이관 커밋(8f213de)에서는 수정되지 않았다(`git log` 확인). 즉 통과시키려고 고친 흔적은 없다 | 사람이 "베이스라인 선행 커밋"으로 인정하면 기각 가능. 스냅샷은 레거시(8081)에서 찍은 것이고 새 API 에서도 같은 결과로 통과했다 |
| 3 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchRow.java:17` | `item.getTags()` 가 `LAZY`(`Item.java:54`)이고 검색 쿼리는 태그를 fetch 하지 않아(`ItemRepository` 주석) 페이지당 최대 20건의 태그 조회가 추가로 나간다(N+1). 트랜잭션 안(`ItemService` 클래스 `readOnly`)이라 오류는 아니다 | `@BatchSize` 또는 id 목록으로 태그를 한 번에 조회하는 방안 검토. 동작 변경이 없는 범위에서만 |
| 4 | 제안 | `modern/api/src/main/java/com/example/item/ItemSpecifications.java:28` | 키워드 앞 `%` 를 붙인 LIKE 라 인덱스를 못 타고, 사용자가 넣은 `%` · `_` 가 와일드카드로 동작한다. 레거시 BR-04 와 같은 동작이라 이번 이관에서는 맞다 | 이관 중에는 고치지 않는다. 아래 "의심 동작"으로 따로 남긴다 |

## 의심 동작 (레거시 보존, 이관 중 수정하지 않음)

- 키워드 `%` · `_` 를 이스케이프하지 않고 와일드카드로 쓴다(`ItemSpecifications.java:28`, BR-04). 전체 스캔 유발 가능.
- `level=6` 같은 범위 밖 값, `3abc` 같은 값을 오류 없이 PHP `(int)` 변환으로 조회한다(`ItemSearchCondition.java` `phpIntval`, BR-09).
- `page` 에만 trim 을 하지 않는다(`" 2"` → 1, `ItemSearchCondition.java` `resolvePage`).

## 확인 필요

- `characterization/tests/example-units.test.js` — 새 API 대상 실행에서 1건이 skipped 였다. 해당 파일 21행의 `it.skipIf(legacyOnly)` 로 `TARGET_BASE_URL` 이 있으면 의도적으로 건너뛰는 레거시 전용 예시이고, 이번 diff 에 포함되지 않은 기존 파일이다. 의도된 skip 으로 보이나 사람이 확인한다.
- 새 API(8080) 서버가 현재 HEAD 소스로 떠 있는지 — 이미 떠 있던 서버를 그대로 썼다. 검색 29건이 통과해 엔드포인트가 존재하는 것은 확인했으나, 기동 시점 소스가 HEAD 와 같은지는 확인하지 못했다.
- `modern/web` — 변경 없음으로 테스트를 실행하지 않았다(실행하지 않음).

## 반려 사유와 다음 단계

테스트 · 치명 이슈 · 컨벤션은 문제가 없고, 반려 사유는 "요청 범위 이탈 없음" 한 기준이다(이슈 1). 사람이 비이관 파일 12개의 포함 여부를 확정하거나 PR 을 분리하고 비교 기준을 정리하면 이관 코드 자체는 승인 가능한 상태로 보인다. 이 리뷰 세션은 코드를 수정하지 않았다.
