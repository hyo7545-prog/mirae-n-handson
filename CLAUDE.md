# CLAUDE.md

Claude Code 심화 과정 실습 저장소다. 코드 · 데이터 · 로그는 모두 더미 에듀테크 도메인(문항 은행 · 과제 배포 · 성적 집계)이다.

- 답변과 문서는 한국어로 쓴다.
- `legacy/` 는 분석 · 이관 대상이다. 허락 없이 수정하지 않는다.

## 1. 빌드 · 테스트 명령

Claude Code 는 저장소 루트에서 실행하고, 명령은 아래처럼 해당 프로젝트 폴더로 들어가서 실행한다.

```bash
# modern/api — 테스트는 H2(test 프로필)라서 DB 없이 통과해야 한다
cd modern/api && ./gradlew test
cd modern/api && ./gradlew test --tests 'com.example.item.ItemServiceTest'   # 클래스 하나만
cd modern/api && ./gradlew bootRun          # 먼저 루트에서 docker compose --profile modern up -d

# modern/web
cd modern/web && npm ci                     # npm install 금지(package-lock.json 이 바뀐다)
cd modern/web && npm run dev                # http://localhost:5173
cd modern/web && npm run lint && npm run typecheck && npm test
cd modern/web && npx vitest run src/components/ItemTable.test.tsx           # 파일 하나만
cd modern/web && npm run build
```

- 작업을 끝내기 전에 바꾼 쪽의 테스트 명령(api: `./gradlew test`, web: lint · typecheck · test 세 개)을 실행하고, 통과 · 실패 수를 답변에 적는다. 실행하지 않았으면 "실행하지 않음"이라고 적는다.
- docker compose 의 `down` · `build` · `logs` 에도 `--profile <이름>` 을 붙인다.

## 2. 코딩 컨벤션

### 팀 규칙
- 컨트롤러 클래스에는 `*Repository` 타입 필드 · 생성자 파라미터가 없다. 컨트롤러는 같은 도메인의 서비스만 호출한다.
- 예외를 삼키지 않는다: `catch` 블록은 예외를 다시 던지거나 다른 예외로 감싸 던진다. 로그만 남기고 끝나는 `catch` 와 빈 `catch` 는 없다. 로그는 SLF4J(`LoggerFactory.getLogger(<클래스>.class)`)로만 남긴다.
- 의존성 추가 · 변경(`build.gradle` 의 `dependencies`, `package.json`)과 DB 스키마 · 시드 변경(`db/**/init/`)은 파일을 고치기 전에 사람에게 묻는다.

### modern/api
- 패키지는 도메인 단위(`com.example.item`, `com.example.assignment`, `com.example.common`)이고 호출 방향은 Controller → Service → Repository → 엔티티다.
- 서비스는 다른 도메인의 Repository 를 주입하지 않는다. 다른 도메인 데이터는 그 도메인의 서비스를 통해 읽는다.
- 컨트롤러 메서드의 반환 타입에 엔티티 클래스가 없다. 응답은 record DTO 이고, 서비스의 트랜잭션 안에서 `XxxResponse.from(entity)` 로 바꾼다(`open-in-view: false`).
- 조회 서비스 메서드는 `@Transactional(readOnly = true)`, 쓰기 메서드만 `@Transactional` 을 붙인다.
- 컨트롤러 메서드 안에 try-catch 가 없다. 예외 → HTTP 변환은 `common/GlobalExceptionHandler` 에만 있다: `NotFoundException` 404, 검증 · 타입 오류와 `IllegalArgumentException` 400, `IllegalStateException` 409, 나머지 500.
- 현재 시각은 `ClockConfig` 의 `Clock` 빈을 주입받아 얻는다. `LocalDateTime.now()` 처럼 인자 없는 `now()` 를 쓰지 않는다.
- 공개 문항은 `status = 'A'`(`ItemStatus.ACTIVE`)로 판단한다.
- 로그 문자열에 학생 식별자(`STU-…`) · 이메일 · 토큰 값을 넣지 않는다.
- 이름: 클래스 `PascalCase`, 메서드 · 필드 `camelCase`, 상수 `UPPER_SNAKE_CASE`. 비교 · 계산에 쓰는 숫자 리터럴(0 · 1 제외)은 `static final` 상수로 둔다.
- 들여쓰기 4칸, 한 줄 120자 이내, 와일드카드 import(`.*`) 없음.
- 테스트: 컨트롤러 `@WebMvcTest`, 서비스 Mockito, 리포지토리 `@DataJpaTest`(H2), 전체 컨텍스트 `@SpringBootTest @ActiveProfiles("test")`. 메서드 이름은 camelCase, `@DisplayName` 에 한국어 설명.
- 새 public 서비스 메서드 · 새 엔드포인트마다 테스트가 1개 이상 있다.

### modern/web
- 모든 HTTP 요청은 `src/api/client.ts` 의 `getJson` 을 거친다. `client.ts` 밖에서 `fetch` 를 호출하지 않는다.
- 조회 훅은 `src/hooks/` 에 두고 `useApiQuery` 위에 만든다. 컴포넌트는 훅이나 props 로만 서버 데이터를 받는다.
- 오류는 `ApiError` 로 받는다. 컴포넌트 안에서 `response.status` 같은 HTTP 상태 코드를 직접 비교하지 않는다.
- 함수 컴포넌트만 쓴다. 클래스 컴포넌트 · `React.FC` · `defaultProps` 가 없다.
- 응답 타입은 `src/api/types.ts` 에 두고 백엔드 JSON 필드명을 그대로 쓴다.
- 이벤트 핸들러 prop 은 `on<동작>`, 구현 함수는 `handle<동작>` 으로 짓는다. 훅 이름은 `use` 로 시작한다.
- 테스트는 Vitest + Testing Library 이고 `<이름>.test.tsx` 를 대상 파일 옆에 둔다. 네트워크는 `src/test/mockFetch.ts` 로 가로챈다(등록하지 않은 경로는 404). 테스트가 실제 `localhost:8080` 을 호출하지 않는다.
- 조회는 `getByRole` · `getByLabelText` 를 먼저 쓰고 `getByTestId` 는 둘로 찾을 수 없을 때만 쓴다.
- `eslint-disable` 주석에는 같은 줄에 사유를 적는다.

## 3. 금지 사항

- `legacy/` 아래 파일 수정(허락받은 경우 제외).
- `modern/api` 의 `application.yml` 에서 DB 접속 정보 · Hikari 풀 설정(max 5, timeout 3s, 운영 값과 동일) 변경.
- `@Transactional` 메서드 안에서 외부 HTTP 호출이나 DB 작업과 무관한 긴 루프 실행. 트랜잭션을 오래 쥐면 곧바로 풀이 고갈된다.
- 실제 비밀값(운영 계정 · 토큰 · 키)을 코드 · 설정에 리터럴로 넣기. 실습용 더미 값(`app-pass`)은 예외다.
- 운영 DB 호스트(`prod-db` 등)에 접속하는 명령 · 설정 작성.
- Java: `System.out` · `System.err` · `e.printStackTrace()`.
- TypeScript: `any`, `as unknown as`, `@ts-ignore`, `dangerouslySetInnerHTML`, 커밋에 남은 `console.log`.
- `package-lock.json` 을 손으로 고치거나 지우기.
- 요청 범위 밖 파일의 포맷팅 · import 정리 · 리팩터링.
- `characterization/` 스냅샷을 깨뜨리는 변경을 알리지 않고 진행하기.
- 루트 `.env` 생성(권한 실습용 더미는 `.env.perm-test`).
- 분석 작업에 쓰기 DB 계정(`app` 등) 사용. 쓰기 계정은 compose · `application.yml` 안에서만 쓴다.
- 아래 "일부러 없는 것"을 요청 없이 미리 만들기.

## 4. 아키텍처 안내

### 레거시 → 현행 이관
- `legacy/`(이관 원본): `item-bank-php/`(PHP 7.4, 8081), `assignment-thymeleaf/`(Spring MVC + Thymeleaf + JDBC, 8082), `grade-mssql/`(MS-SQL 저장 프로시저 + Java 호출부, 8083).
- `modern/`(이관 대상 · 팀 컨벤션 기준): `api/`(Spring Boot 3.3 · Java 21, 8080), `web/`(React 18 + TS + Vite, 5173).
- `characterization/`: 레거시 응답을 스냅샷으로 찍고 `TARGET_BASE_URL` 만 바꿔 새 API 와 비교한다.
  - 응답은 `{status, rows[], count, message}` 로 정규화한다(`lib/normalize.mjs`).
  - 대상 주소는 `lib/target.mjs` 에만 둔다. 테스트는 `fetchNormalized(모듈명, 레거시경로, 파라미터)` 만 쓴다.
  - 경로가 바뀌면 `PATH_ALIASES` 에 한 줄 추가한다. 테스트 코드와 스냅샷은 그대로 둔다.
  - 모듈명은 `item-bank` · `assignment` · `grade`. 테스트 `tests/<모듈명>.test.js`, 스냅샷은 커밋한다. 순차 실행(`fileParallelism: false`).
- 레거시 규칙을 옮길 때는 근거를 `파일:줄번호` 로 답변에 적는다.

### modern/api
```
src/main/java/com/example/
├── item/        문항 · 단원 · 태그 (ItemController, UnitController, ItemService, UnitService, *Repository)
├── assignment/  과제 배포 · 재배포 · 학급 리포트 (Distribution*, Report*, Submission, ClassRoom)
├── common/      GlobalExceptionHandler, ErrorResponse, NotFoundException, ClockConfig
└── config/      WebConfig (CORS)
src/main/resources/application.yml       로컬 MariaDB + Hikari 풀
src/test/resources/application-test.yml  테스트 프로필 = H2
```
- 엔드포인트: `GET /api/units`, `GET /api/units/{code}/items`, `GET /api/items/{id}`, `GET /api/distributions/{id}`, `POST /api/distributions/{id}/redistribute`, `GET /api/classes/{id}/report`. 새 엔드포인트는 `/api/<도메인 복수형>` 아래에 둔다.

### modern/web
```
src/
├── api/         client.ts(getJson · ApiError) · items.ts · types.ts
├── hooks/       useApiQuery(key 기반 재조회 · AbortSignal 취소 · idle|loading|success|error) 와 조회 훅
├── components/  화면 컴포넌트와 테스트
└── test/        mockFetch.ts · fixtures.ts
```
- 기본 URL 은 `VITE_API_BASE`, 없으면 `http://localhost:8080`. `VITE_API_BASE=""` 이면 Vite 프록시(`/api` → 8080)를 탄다.

### DB · 인프라
- 스키마 · 시드: `db/mariadb/init/`, `db/mssql/init/`. `php` · `thymeleaf` · `modern` 프로필은 MariaDB(`itembank`) 하나를 같이 쓴다. tmpfs 라서 `down` 뒤 `up` 하면 시드 상태로 돌아간다.
- 읽기 전용 계정: MariaDB `localhost:3306/itembank` `readonly`/`readonly-pass`, MS-SQL `localhost:1433/grades` `readonly`/`Readonly-pass1`.
- Hook 은 `bash "$CLAUDE_PROJECT_DIR"/scripts/hook-node.sh hooks/<파일>.mjs` 형태로 등록한다(node 가 없으면 막는다).

### 도메인 용어
문항 `item` · 단원 `unit` · 난이도 `level`(1~5) · 태그 `tag` / 학급 `class` · 과제 `assignment` · 배포 `distribution` · 제출 `submission` / 학생 `STU-<숫자>`.

### 일부러 없는 것 (요청받았을 때만 만든다)
- 하위 폴더 `CLAUDE.md`, `.claude/skills` · `.claude/agents` · `.claude/settings.json`, `hooks/*.mjs`, `.mcp.json`, `dbhub.toml`
- `modern/api` 의 `/api/items/search` 이관 엔드포인트
- `characterization/tests/<모듈>.test.js` 와 그 스냅샷

### 그 밖의 폴더
`vendor-prs/`(리뷰할 패치), `incident-logs/`(장애 시나리오), `mcp-skeleton/`(MCP SDK v2 고정), `pipeline-samples/`, `ci-ports/`, `specs/`, `templates/`(CLAUDE.md 템플릿 · 체크리스트).

## 5. 완료 기준

- 이관 · 리팩토링 작업은 `characterization/` 의 `npm test` 가 전부 통과하기 전에는 완료라고 보고하지 않는다.
- 테스트가 실패하면 실패한 케이스와 그 차이를 그대로 보고한다. 요약해서 "거의 됐다"고 말하지 않는다.
- 테스트를 통과시키려고 `characterization/` 의 테스트 코드나 스냅샷 파일을 고치지 않는다. 스냅샷을 바꿔야 한다고 판단되면 멈추고 사람에게 묻는다.
- 레거시 동작이 버그로 보여도 이관 중에는 고치지 않는다. 그런 동작은 "의심 동작" 목록으로 따로 보고한다.
