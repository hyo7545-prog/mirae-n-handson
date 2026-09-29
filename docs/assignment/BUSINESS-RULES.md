# assignment-thymeleaf 비즈니스 규칙 후보 — 새 배포 화면

> 대상: `legacy/assignment-thymeleaf/` (읽기 전용 분석 결과)
> **범위: "새 배포" 화면 하나** — `GET /distributions/new`(E1), `POST /distributions`(E2)와 이 둘이 호출하는 코드만. 목록 · 재배포 화면은 범위 밖이다.
> 경로 표기: `legacy/assignment-thymeleaf/src/main/` 기준이고, Java 파일은 `java/com/example/assign/` 을 생략한다(예: `web/DistributionController.java`). 템플릿은 `templates/`, 스키마 · 시드는 저장소 루트 기준이다.
> 모든 규칙은 코드를 읽어서만 판단했다. 애플리케이션을 실행하거나 DB 를 조회하지 않았다.

## 규칙

### BR-01 폼의 과제 목록은 상태 O · X · C 만, 마감 빠른 순으로 보인다
- 규칙: 새 배포 폼의 과제 선택 목록은 `assignment.status` 가 `O` · `X` · `C` 인 과제만 보여 준다. 정렬은 `due_at` 오름차순, 같으면 `id` 오름차순이다. `D` 등 그 밖의 상태는 목록에 나오지 않는다.
- 근거: `dao/AssignmentDao.java:36-37`, `web/DistributionController.java:42`
- 근거 코드:
  ```java
                  + " WHERE a.status IN ('O', 'X', 'C') "
                  + " ORDER BY a.due_at ASC, a.id ASC";
  ```
- 확신도: 확실
- 비고: 목록에서만 거르는 필터다. 서버 쪽 등록(E2)은 이 필터를 다시 적용하지 않는다(BR-09 참고).

### BR-02 폼의 학급 목록은 전체 학급을 id 순으로 보인다
- 규칙: 학급 선택 목록은 `class` 전체를 `id` 오름차순으로 보여 준다. 거르는 조건은 없다.
- 근거: `dao/ClassDao.java:21`, `web/DistributionController.java:43`
- 근거 코드:
  ```java
          return jdbc.query("SELECT id, name, teacher_id FROM `class` ORDER BY id ASC", MAPPER);
  ```
- 확신도: 확실
- 비고: 옵션 문구는 `이름 (teacher_id)`이다(`templates/distribution_form.html:25`).

### BR-03 "현재 시각"은 2026-09-15 00:00:00 으로 고정되어 있다
- 규칙: JVM 시스템 속성 `app.clock` 이 `system` 이 아니면 `AppClock.now()` 는 항상 `2026-09-15 00:00:00` 을 돌려준다. `system` 일 때만 실제 시각(나노초 0)을 돌려준다. 이 화면의 마감 판단(BR-04 · BR-05)과 `distributed_at`(BR-11)이 모두 이 값을 쓴다.
- 근거: `AppClock.java:9`, `AppClock.java:14-19`
- 근거 코드:
  ```java
      private static final String FIXED = "2026-09-15 00:00:00";
          if (sys != null && sys.equals("system")) {
          return LocalDateTime.parse(FIXED, FMT);
  ```
- 확신도: 확실
- 비고: 주석(`:8`)은 "운영 반영 전 되돌릴 것 (아직 안 되돌림)"이라고 적고 있다. 코드는 고정값 그대로다. 시드 기준으로 보면 고정 시각에서는 과제 1 · 2 · 3 이 마감 지남이고 과제 4(`2026-09-25 23:59`, `02-seed.sql:123`)는 배포할 수 있다. 실제 시각(2026-09-29)이라면 과제 4 도 마감 지남이 된다. 추정, 실행 확인 안 함. 컨테이너가 `-Dapp.clock` 을 넘기는지는 범위 밖(Dockerfile · compose)이라 확인하지 않았다.

### BR-04 폼에서 마감이 지난 과제는 선택할 수 없다 (연장 X 는 예외)
- 규칙: `dueAt` 이 null 이 아니고 현재 시각(BR-03)보다 앞서며 상태가 `X` 가 아니면 그 옵션은 `disabled` 다. 마감이 지난 옵션의 문구 끝에는 상태가 `X` 이면 ` [연장]`, 아니면 ` [마감]` 이 붙는다.
- 근거: `templates/distribution_form.html:16`, `templates/distribution_form.html:18`
- 근거 코드:
  ```html
                      th:disabled="${a.dueAt != null and a.dueAt.isBefore(now) and a.status != 'X'}"
                               + (${a.dueAt != null and a.dueAt.isBefore(now)} ? (${a.status == 'X'} ? ' [연장]' : ' [마감]') : '')"></option>
  ```
- 확신도: 확실
- 비고: 클라이언트 쪽 제한이라 요청을 직접 만들면 우회된다. 서버는 BR-05 에서 같은 조건을 다시 검사한다(중복 구현). `isBefore` 라서 마감 시각과 현재 시각이 같으면 선택할 수 있다. 옵션의 마감 표시는 `MM-dd HH:mm` 형식이다(`:17`).

### BR-05 서버: 마감이 지난 과제는 새로 배포할 수 없다 (연장 X 는 예외)
- 규칙: E2 에서 과제의 `dueAt` 이 null 이 아니고 현재 시각(BR-03)보다 앞서며 상태가 `X` 가 아니면, 배포하지 않고 flash `error` "마감(yyyy-MM-dd HH:mm)이 지난 과제는 배포할 수 없습니다. [제목]"을 담아 `/distributions/new` 로 리다이렉트한다.
- 근거: `web/DistributionController.java:60-63`
- 근거 코드:
  ```java
          if (a.getDueAt() != null && a.getDueAt().isBefore(now)) {
              if (!"X".equals(a.getStatus())) {
                  ra.addFlashAttribute("error", "마감(" + AppClock.fmt(a.getDueAt()) + ")이 지난 과제는 배포할 수 없습니다. [" + a.getTitle() + "]");
  ```
- 확신도: 확실
- 비고: 이 검사는 **컨트롤러에만 있다**. `DistributionService.distribute`(`service/DistributionService.java:47-68`)에는 마감 검사가 없다. 템플릿(BR-04)과 중복 구현이다. `due_at` 은 `NOT NULL`(`01-schema.sql:86`)이라 `getDueAt() != null` 조건은 DB 데이터로는 늘 참이다(방어 코드). 추정, 실행 확인 안 함. 시드에 `X` 과제가 없어서(`02-seed.sql:120-125`) 연장 예외 경로는 시드로 재현되지 않는다.

### BR-06 존재하지 않는 과제는 배포할 수 없다
- 규칙: `assignmentId` 로 과제를 찾지 못하면, 컨트롤러는 flash `error` "존재하지 않는 과제입니다."를 담아 `/distributions/new` 로 리다이렉트한다. 서비스도 같은 경우에 `IllegalArgumentException` 을 던진다.
- 근거: `web/DistributionController.java:53-56`, `service/DistributionService.java:48-51`
- 근거 코드:
  ```java
          if (a == null) {
              ra.addFlashAttribute("error", "존재하지 않는 과제입니다.");
              return "redirect:/distributions/new";
  ```
- 확신도: 확실
- 비고: 중복 구현이다. 컨트롤러가 먼저 걸러서 서비스 쪽 분기(메시지에 `(id=…)` 포함)는 두 조회 사이에 과제가 지워지지 않는 한 도달하지 않는다. 추정, 실행 확인 안 함.

### BR-07 존재하지 않는 학급은 배포할 수 없다
- 규칙: `classId` 로 학급을 찾지 못하면 서비스가 `IllegalArgumentException("존재하지 않는 학급입니다. (id=<classId>)")` 를 던진다. 컨트롤러는 이 메시지를 flash `error` 로 담아 `/distributions/new` 로 리다이렉트한다.
- 근거: `service/DistributionService.java:52-55`, `web/DistributionController.java:69-71`
- 근거 코드:
  ```java
          ClassRow c = classDao.findById(classId);
          if (c == null) {
              throw new IllegalArgumentException("존재하지 않는 학급입니다. (id=" + classId + ")");
  ```
- 확신도: 확실
- 비고: 학급 검사는 컨트롤러에는 없고 서비스에만 있다.

### BR-08 삭제(D) 상태 과제는 배포할 수 없다
- 규칙: 과제 상태가 `D` 이면 서비스가 `IllegalStateException("삭제된 과제는 배포할 수 없습니다.")` 을 던진다. 컨트롤러는 이를 flash `error` 로 바꾼다.
- 근거: `service/DistributionService.java:56-58`
- 근거 코드:
  ```java
          if ("D".equals(a.getStatus())) {
              throw new IllegalStateException("삭제된 과제는 배포할 수 없습니다.");
  ```
- 확신도: 확실
- 비고: 폼 목록(BR-01)에 `D` 가 나오지 않으므로 요청을 직접 만들 때만 도달한다. 추정, 실행 확인 안 함. 검사 순서상 BR-05 가 먼저라서, 마감이 지난 `D` 과제는 이 메시지가 아니라 마감 메시지를 받는다(`web/DistributionController.java:60-67`).

### BR-09 서버는 D 와 "마감 지남 + X 아님" 말고는 과제 상태로 막지 않는다
- 규칙: E2 에서 과제 상태로 거절하는 조건은 BR-05 · BR-08 둘뿐이다. 그래서 폼 목록(BR-01)에 없는 상태의 과제도, 마감 전이면 요청을 직접 만들어 배포할 수 있다.
- 근거: `web/DistributionController.java:60-61`, `service/DistributionService.java:56`, 비교 대상 `dao/AssignmentDao.java:36`
- 근거 코드:
  ```java
          if ("D".equals(a.getStatus())) {
  ```
- 확신도: 추정
- 비고: 코드에 있는 분기가 이것뿐이라는 데서 끌어낸 결론이다. 추정, 실행 확인 안 함. 그 밖의 상태 값으로 `R` 이 범위 밖 화면(`templates/assignments.html:26`)에 보이지만, 범위 안 코드에는 없다. 시드에는 `O` · `C` 만 있다.

### BR-10 같은 과제를 같은 학급에 두 번 배포할 수 없다 (코드로만 막음)
- 규칙: `distribution` 에 같은 (`assignment_id`, `class_id`) 행이 1건 이상 있으면 서비스가 `IllegalStateException("이미 이 학급에 배포된 과제입니다. 다시 내려면 배포 목록에서 재배포를 사용하세요.")` 을 던진다.
- 근거: `service/DistributionService.java:59-60`, `dao/DistributionDao.java:49`
- 근거 코드:
  ```java
          if (distributionDao.countByAssignmentAndClass(assignmentId, classId) > 0) {
              throw new IllegalStateException("이미 이 학급에 배포된 과제입니다. 다시 내려면 배포 목록에서 재배포를 사용하세요.");
  ```
- 확신도: 확실
- 비고: DB 에 UNIQUE 제약이 없다(`01-schema.sql:92-103`, `dao/DistributionDao.java:46` 주석도 같은 내용). 세고 나서 INSERT 하는 방식이라 동시에 요청하면 둘 다 통과할 수 있다. 추정, 실행 확인 안 함. 시드에 이미 같은 쌍이 두 건 있다(`02-seed.sql:133-134`, 과제 2 · 학급 1).

### BR-11 새 배포 id 는 MAX(id)+1 이고, 재배포 플래그는 0, 배포 시각은 AppClock.now() 다
- 규칙: 새 배포의 `id` 는 `distribution` 의 최대 id + 1(행이 없으면 1)이다. 새 행의 `redistributed` 는 `0`, `distributed_at` 은 `AppClock.now()`(BR-03)로 INSERT 한다.
- 근거: `dao/DistributionDao.java:55-56`, `dao/DistributionDao.java:61`, `service/DistributionService.java:62-63`
- 근거 코드:
  ```java
          Long max = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM distribution", Long.class);
          return (max == null ? 0L : max.longValue()) + 1L;
                  "INSERT INTO distribution (id, assignment_id, class_id, distributed_at, redistributed) VALUES (?, ?, ?, ?, 0)",
  ```
- 확신도: 확실
- 비고: `distribution.id` 에는 AUTO_INCREMENT 가 없다(`01-schema.sql:93`). 동시 요청에서 같은 id 가 나오면 PK 충돌 → `DataAccessException` → BR-13 의 DB 오류 메시지로 끝날 것이다. 추정, 실행 확인 안 함.

### BR-12 INSERT 영향 행이 1이 아니면 실패로 본다
- 규칙: `insert` 가 돌려준 영향 행 수가 1이 아니면 서비스가 `IllegalStateException("배포 저장에 실패했습니다.")` 을 던진다.
- 근거: `service/DistributionService.java:64-66`
- 근거 코드:
  ```java
          if (n != 1) {
              throw new IllegalStateException("배포 저장에 실패했습니다.");
  ```
- 확신도: 확실
- 비고: 단일 행 INSERT 에서 1 이 아닌 값이 나오는 경우는 코드에서 찾지 못했다.

### BR-13 등록 결과에 따른 이동과 메시지
- 규칙: 서비스가 성공하면 flash `message` "배포가 등록되었습니다. (배포 #<id>)"를 담아 `/distributions` 로 간다. `IllegalArgumentException` · `IllegalStateException` 은 그 메시지를, `DataAccessException` 은 고정 문구 "DB 오류로 배포에 실패했습니다."를 flash `error` 로 담아 `/distributions/new` 로 돌아간다.
- 근거: `web/DistributionController.java:68`, `web/DistributionController.java:69-74`, `web/DistributionController.java:76`
- 근거 코드:
  ```java
          } catch (IllegalArgumentException | IllegalStateException e) {
              ra.addFlashAttribute("error", e.getMessage());
          } catch (DataAccessException e) {
  ```
- 확신도: 확실
- 비고: try 블록 밖인 `:53` 의 조회에서 `DataAccessException` 이 나면 `DbErrorAdvice`(`web/DbErrorAdvice.java:11-16`)가 받아 `db_error` 화면을 보여 준다. 추정, 실행 확인 안 함. `DbErrorAdvice` 는 `System.out.println` 으로 로그를 남긴다(`:13`).

### BR-14 과제 · 학급 선택은 브라우저에서 필수다
- 규칙: 두 `select` 에 `required` 가 있고 첫 옵션 값이 빈 문자열이다. 선택하지 않으면 브라우저가 제출을 막는다.
- 근거: `templates/distribution_form.html:11-12`, `templates/distribution_form.html:23-24`
- 근거 코드:
  ```html
          <select id="assignmentId" name="assignmentId" required>
              <option value="">-- 과제 선택 --</option>
  ```
- 확신도: 확실
- 비고: 서버 쪽 처리는 `@RequestParam long` 바인딩(`web/DistributionController.java:50-51`)뿐이고, 값이 없거나 숫자가 아닐 때 어떻게 되는지는 코드에 없다("찾지 못한 것" 참고).

## 매직 넘버 · 리터럴

| 값 | 뜻(코드 기준) | 위치 |
|---|---|---|
| `"2026-09-15 00:00:00"` | 고정 현재 시각 | `AppClock.java:9` |
| `"system"` (`app.clock`) | 이 값일 때만 실제 시각 사용 | `AppClock.java:15-16` |
| `'O'`, `'X'`, `'C'` | 폼 목록에 보이는 과제 상태 | `dao/AssignmentDao.java:36` |
| `'X'` | 마감 지남 검사의 예외 상태 | `web/DistributionController.java:61`, `templates/distribution_form.html:16, 18` |
| `'D'` | 배포 거절 상태 | `service/DistributionService.java:56` |
| `0` | 새 배포의 `redistributed` 값 | `dao/DistributionDao.java:61` |
| `0`, `1L` | 채번: 행이 없으면 0, 거기에 +1 | `dao/DistributionDao.java:55-56` |
| `1` | INSERT 성공으로 보는 영향 행 수 | `service/DistributionService.java:64` |
| `> 0` | 중복 배포 판단 기준 | `service/DistributionService.java:59` |
| `"yyyy-MM-dd HH:mm"` | 서버 오류 메시지의 마감 표시 형식 | `AppClock.java:12` (사용 `web/DistributionController.java:62`) |
| `'MM-dd HH:mm'` | 폼 옵션의 마감 표시 형식 | `templates/distribution_form.html:17` |
| `4`, `5000`, `-1` | Hikari 최대 풀 · 연결 타임아웃(ms) · 초기화 실패 타임아웃 | `resources/application.properties:6-8` |
| `v1.4.2` | 화면 꼬리말 버전 | `templates/layout.html:35` |

## 주석만 있거나 코드와 다른 것

| 주석 · 문구 | 코드 동작 | 위치 |
|---|---|---|
| "운영 반영 전 LocalDateTime.now() 로 되돌릴 것 (아직 안 되돌림)" | 여전히 `2026-09-15 00:00:00` 고정이다(BR-03) | 주석 `AppClock.java:8`, 코드 `:9, :19` |
| 스키마 주석 `O=진행 C=마감` (두 값만) | 코드는 `X`(연장) · `D`(삭제)도 쓴다 | 주석 `01-schema.sql:87`, 코드 `dao/AssignmentDao.java:36`, `service/DistributionService.java:56`, `web/DistributionController.java:61` |
| 서버 오류 메시지의 마감 형식 `yyyy-MM-dd HH:mm` | 폼 옵션은 `MM-dd HH:mm` 이다. 같은 값을 두 형식으로 보여 준다 | `AppClock.java:12`, `templates/distribution_form.html:17` |
| `due_at DATETIME NOT NULL` | 코드는 `dueAt != null` 을 검사한다(도달하지 않는 방어 분기) | `01-schema.sql:86`, `web/DistributionController.java:60`, `templates/distribution_form.html:16` |

- 코드와 일치하는 주석: `templates/distribution_form.html:13` ↔ `:16`, `web/DistributionController.java:59` ↔ `:60-61`, `dao/DistributionDao.java:46` ↔ `:47-52` 와 DDL.

## 찾지 못한 것

- **`X`(연장) 상태의 뜻과 전이**: 과제를 `X` 로 바꾸는 코드는 범위 안에 없다. "연장"이라는 이름은 화면 문구(`templates/distribution_form.html:18`)에서 나왔고, 연장 기한 같은 별도 컬럼도 스키마에 없다(`01-schema.sql:82-90`).
- **파라미터 누락 · 형식 오류**: `assignmentId` · `classId` 가 없거나 숫자가 아닐 때 어떻게 응답하는지는 코드에 처리가 없다. 프레임워크 기본 동작에 맡겨져 있다.
- **트랜잭션 롤백 범위**: `distribute` 는 `@Transactional`(`service/DistributionService.java:46`)이지만, 쓰기는 마지막 INSERT 하나뿐이다. 실패 시 되돌릴 다른 쓰기가 있는지, 격리 수준이 무엇인지는 코드에 없다.
- **동시 요청 결과**: BR-10 · BR-11 의 경합이 실제로 중복 행을 만드는지, PK 충돌로 끝나는지는 실행해 보지 않아서 모른다.
- **권한 · 담당 교사 검사**: 학급의 `teacher_id` 와 요청자를 비교하는 코드는 없다(`teacher_id` 는 표시에만 쓴다, `templates/distribution_form.html:25`).
