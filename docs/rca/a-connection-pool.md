# 장애 원인 분석 — a-connection-pool

대상 로그: `incident-logs/a-connection-pool/` (2026-09-16 하루치)

## 요약

- **무슨 일**: 2026-09-16 **14:40:52 ~ 14:51:47 KST(약 11분)** API가 5xx 186건(500 49 · 502 82 · 504 55)을 냈다. 교사 리포트뿐 아니라 학생 문항 경로(`/api/units…`, `/api/items…`)도 실패했다. 첫 이상 신호는 14:37:42(느린 쿼리) · 14:37:52(커넥션 누수 경고)이고, 14:52부터 평소 수준이다.
- **원인**: 학급 리포트(`ReportService.buildClassReport`)가 트랜잭션 안에서 DB와 무관한 서명 루프(`sign()`)까지 돌며 커넥션을 15.5 ~ 21.5초씩 쥐었다. 14:38부터 리포트 요청이 늘자 크기 5인 커넥션 풀이 리포트에 모두 묶였고, 다른 API는 3초 대기 끝에 실패했다.
- **확신 수준: 중간**. 로그 스택 · 풀 설정 · 코드가 같은 메커니즘을 가리킨다. 하지만 같은 코드의 점유 시간이 14:37 전후로 10초 미만 → 15.5초 이상으로 늘어난 이유와 운영 배포본이 저장소 코드와 같은지는 확인하지 못했다(9절).

## 1. 수집 범위

> 이 절은 분석할 시간 범위만 정한다. 원인은 다루지 않는다.
> 파일은 통째로 읽지 않고 `wc`, `head`, `tail`, `grep -c`, `grep -n` 결과만으로 정리했다.

### 1.1 파일별 기록 기간 · 형식

| 파일 | 줄 수 | 기록 기간 (KST) | 시각 형식 (예) | 타임존 |
|---|---:|---|---|---|
| `app.log` | 5,371 (시각이 찍힌 항목 1,927 + 스택 트레이스 등 시각 없는 줄 3,444) | 06:43:56 ~ 15:29:51 | ISO-8601, 밀리초 · 오프셋 포함<br>`2026-09-16T14:37:52.199+09:00` | +09:00 (1,927건 모두) |
| `nginx-access.log` | 21,086 | 06:43:56 ~ 15:29:59 | Common Log Format<br>`[16/Sep/2026:14:41:06 +0900]` | +0900 (21,086건 모두) |
| `nginx-error.log` | 168 | 09:12:03 ~ 14:51:47 | `2026/09/16 14:41:06` (오프셋 없음) | 표기 없음 → **KST로 판단** (아래 근거) |
| `mariadb-slow.log` | 21 (쿼리 3건) | 09:14:07 ~ 14:37:52 | `# Time: 260916  5:37:52` (YYMMDD, 시 앞자리 0 없음)<br>`SET timestamp=1789537062;` (epoch 초) | `# Time` 은 **UTC**, `SET timestamp` 는 epoch(UTC) |

**KST 변환 규칙**

- `app.log`, `nginx-access.log`: 오프셋이 +09:00이라 그대로 KST다.
- `nginx-error.log`: 오프셋이 없다. 다만 `upstream timed out` 줄의 시각(14:41:06, 14:41:26, 14:41:51, 14:42:01, 14:42:13 …)이 `nginx-access.log` 의 504 행 시각과 초 단위까지 일치하므로 KST로 보고 **그대로 쓴다**.
- `mariadb-slow.log`: `# Time` 에 **+9시간** 하면 KST다. `# Time` 은 `SET timestamp` + `Query_time` 과 맞아떨어진다(05:37:42 UTC + 9.8초 = 05:37:52). 그래서 `SET timestamp` 는 쿼리 시작 시각, `# Time` 은 완료 시각이다.

  | `# Time` (UTC) | `SET timestamp` → KST (시작) | KST (완료) | Query_time |
  |---|---|---|---:|
  | 260916 0:14:09 | 09:14:07 | 09:14:09 | 2.32s |
  | 260916 2:02:42 | 11:02:40 | 11:02:42 | 2.04s |
  | 260916 5:37:52 | 14:37:42 | 14:37:52 | 9.81s |

- 시각 의미: nginx의 `$time_local` 은 요청이 끝나 로그가 기록된 시각이다. 그래서 504처럼 오래 걸린 요청은 실제로 이 시각보다 앞서 시작됐다.

### 1.2 집계 기준

- `app.log` 는 **시각이 찍힌 첫 줄만** 센다. 스택 트레이스 줄까지 세면 같은 이벤트가 여러 번 잡힌다. 예를 들어 `grep -c 'timed out'` 은 196건이지만 실제 이벤트는 49건이다.
- 집계 항목

  | 파일 | 오류 | 경고 | 5xx | 타임아웃 |
  |---|---|---|---|---|
  | app.log | `ERROR` | `WARN` (그중 `ProxyLeakTask` 는 따로 셈) | — | `Connection is not available, request timed out` |
  | nginx-access.log | — | — | 상태코드 500 · 502 · 504 | 504 |
  | nginx-error.log | `[error]` | `[warn]` | — | `upstream timed out` |
  | mariadb-slow.log | 3건뿐이라 분 단위로 세지 않고 시각만 적음 | | | |

### 1.3 분 단위 건수 (14:30 ~ 14:56, KST)

| 분 | 요청 | 5xx | 504 | app ERROR | app WARN | └ leak | app 풀 타임아웃 | nginx [error] | nginx [warn] | nginx 타임아웃 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 14:30 | 47 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 14:31 | 48 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 14:32 | 44 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 14:33 | 52 | 0 | 0 | 0 | 1¹ | 0 | 0 | 0 | 0 | 0 |
| 14:34 | 34 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 14:35 | 48 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 14:36 | 41 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| **14:37** | 50 | 0 | 0 | 0 | 1 | **1** | 0 | 0 | 0 | 0 |
| 14:38 | 41 | 0 | 0 | 0 | 2 | 2 | 0 | 0 | 0 | 0 |
| 14:39 | 42 | 0 | 0 | 0 | 3 | 3 | 0 | 0 | 0 | 0 |
| 14:40 | 42 | 1 | 0 | 2 | 15 | 14 | 1 | 0 | 0 | 0 |
| 14:41 | 42 | 9 | 3 | 12 | 19 | 13 | 6 | 3 | 0 | 3 |
| 14:42 | 41 | 20 | 5 | 4 | 13 | 10 | 2 | 18 | 2 | 5 |
| 14:43 | 58 | 17 | 5 | 20 | 24 | 14 | 10 | 7 | 1 | 5 |
| 14:44 | 34 | 13 | 6 | 4 | 13 | 11 | 2 | 11 | 4 | 6 |
| 14:45 | 39 | 27 | 5 | 16 | 17 | 9 | 8 | 19 | 3 | 5 |
| 14:46 | 51 | 26 | 6 | 8 | 16 | 12 | 4 | 22 | 3 | 6 |
| 14:47 | 38 | 14 | 3 | 10 | 14 | 9 | 5 | 9 | 1 | 3 |
| 14:48 | 42 | 15 | 5 | 8 | 14 | 10 | 4 | 11 | 4 | 5 |
| 14:49 | 42 | 23 | 8 | 14 | 19 | 12 | 7 | 16 | 4 | 8 |
| 14:50 | 34 | 18 | 7 | 0 | 12 | 11 | 0 | 18 | 4 | 7 |
| **14:51** | 46 | 3 | 2 | 0 | 4 | 3 | 0 | 3 | 0 | 2 |
| 14:52 | 47 | 0 | 0 | 0 | 1¹ | 0 | 0 | 0 | 0 | 0 |
| 14:53 | 37 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 14:54 ~ 14:56 | 42 ~ 46 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

¹ `state conflict` (409) 경고. 평소에도 나오는 경고다(아래 참고).

**평소 수준** (14:37 ~ 14:52 밖의 511분)

| 항목 | 평소 | 구간 안 합계 |
|---|---|---:|
| 요청 수 / 분 | 평균 39.9, 중앙값 41, p95 53, 최대 68 | 평균 43.1 / 분 |
| 5xx | 0 | 186 (500 49 · 502 82 · 504 55) |
| app ERROR | 0 | 98 |
| app leak WARN | 0 | 134 |
| app 풀 타임아웃 | 0 | 49 |
| app WARN (leak 제외) | 분당 최대 1, 16분 · 16건, 모두 `state conflict` | 53 (SQL 경고 49 + `state conflict` 4) |
| nginx [error] | 0 | 137 |
| nginx [warn] | 2건 (09:12, 13:05 `upstream response is buffered`) | 26 (`upstream server temporarily disabled`) |

평소에는 5xx와 오류가 0이고 요청량도 분당 약 40건으로 일정하다. 이상 구간에서도 요청량은 평소와 비슷하다.

**평소에도 나오는 것 (이상 신호에서 제외)**

- app `state conflict: 마감된 과제는 재배포할 수 없습니다` 경고 (409): 08:41 ~ 14:52 동안 흩어져 20건, 분당 최대 1건.
- nginx `[warn] upstream response is buffered to a temporary file`: 09:12, 13:05
- nginx `[info]` 3건: 10:42 ~ 13:18 (`client sent too long header line`, keepalive 종료)
- 4xx 응답 (404 1,250 · 400 25 · 409 20): 하루 내내 나온다.
- slow query 앞의 2건 (09:14, 11:02 KST): 2초대이고 주변에 다른 이상 신호가 없다.

### 1.4 파일별 이상 구간 (KST)

| 파일 | 시작 | 끝 | 근거 |
|---|---|---|---|
| `app.log` | **14:37:52** | **14:51:37** | 첫 · 마지막 `ProxyLeakTask` WARN (L1243 · L5251). ERROR는 14:40:52 ~ 14:49:51 (L1522 · L4981) |
| `nginx-access.log` | **14:40:52** | **14:51:47** | 첫 5xx(500) ~ 마지막 5xx(504). 500은 14:40 ~ 14:49, 502는 14:42 ~ 14:51, 504는 14:41 ~ 14:51 |
| `nginx-error.log` | **14:41:06** | **14:51:47** | 첫 · 마지막 `[error]` (L6 · L168). `[warn] upstream server temporarily disabled` 는 14:42 ~ 14:50 |
| `mariadb-slow.log` | 14:37:42 | 14:37:52 | 9.81초 쿼리 1건 (시작 · 완료) |

**통합 이상 구간 제안: 14:37:42 ~ 14:51:47 KST** (약 14분)

- 시작: 네 파일 중 가장 이른 평소와 다른 기록. slow log의 9.8초 쿼리 시작 시각이고, 10초 뒤 app의 첫 leak 경고가 나온다. 5xx는 그보다 약 3분 늦은 14:40:52에 시작한다.
- 끝: 마지막 5xx · nginx `[error]` (14:51:47). 14:52 이후로는 모든 항목이 평소 수준(0)으로 돌아온다.

### 1.5 앞뒤 여유 제안

| | 여유 | 수집 구간 (KST) | 이유 |
|---|---|---|---|
| 앞쪽 | **30분** | **14:07:00 부터** | 첫 신호는 사후 기록이라 실제 발단은 기록보다 앞선다. leak 경고는 커넥션을 빌린 뒤 일정 시간이 지나야 찍히고, slow log와 nginx 로그는 완료 · 타임아웃 시각에 찍힌다. 또 경고(14:37)가 5xx(14:40)보다 3분 먼저 나타나므로, 그 이전의 요청 패턴 · 배포 · 배치 같은 전조를 볼 여지를 넉넉히 둔다. |
| 뒤쪽 | **10분** | **15:02:00 까지** | 14:52부터 바로 평소 수준으로 돌아온다. 재발하지 않는지, 회복이 안정적인지 확인할 정도면 충분하다. |

- **최종 수집 범위: 2026-09-16 14:07:00 ~ 15:02:00 KST**
  - `mariadb-slow.log` 의 `# Time` 기준으로는 **05:07:00 ~ 06:02:00 UTC**. 이 파일은 21줄뿐이라 통째로 대조해도 된다.
  - `nginx-error.log` 는 KST 표기를 그대로 쓴다.
- 비교용 평소 구간이 필요하면 같은 길이의 13:00 ~ 13:55를 쓴다. 이 구간에는 5xx와 오류가 없고, nginx 13:05 `[warn]` buffered 1건과 13:18 `[info]` 1건만 있다.

## 2. 타임라인

> 대상: 2026-09-16 **14:07:00 ~ 15:02:00 KST** (1절의 수집 범위). 원인 해석은 쓰지 않는다.

**읽는 법**

- 시각은 모두 KST다. `mariadb-slow.log` 는 UTC에 9시간을 더했다.
- 시각 정밀도가 파일마다 다르다. app은 밀리초, nginx와 slow log는 초 단위다. 그래서 파일 간 차이가 **2초 이내**인 곳은 **(선후 불확실)** 로 표시했다. 같은 파일 안의 순서는 줄 번호 순서다.
- nginx 두 로그의 시각은 응답 완료나 타임아웃 시각이다. slow log의 `SET timestamp` 는 쿼리 시작, `# Time` 은 완료 시각이다.
- 같은 메시지가 반복되면 첫 발생 줄 하나만 쓰고, 분별 건수를 `분:건수` 로 붙였다. 예를 들어 `41:6` 은 14:41에 6건이라는 뜻이다.
- 하루 내내 일정하게 나오는 4xx(404 · 400), `no handler for …`, `not found: …` INFO는 넣지 않았다. 409 `state conflict` 와 재배포 성공은 이상 구간 앞의 사건이라 넣고 **(평소)** 로 표시했다.
- 원문 발췌의 `…` 는 생략 표시다.

### 2.1 사건 목록

| 시각 (KST) | 출처 (파일:줄) | 사건 요약 | 원문 발췌 (80자 이내) |
|---|---|---|---|
| 14:10:05 ~ 14:18:13 | access:17539 · 17705 · 17783 · 17880 · 17914 / app:1148 · 1161 · 1167 · 1174 · 1175 | 학급 리포트 조회 5건, 모두 200 **(평소: 띄엄띄엄)**. 이후 14:38까지 리포트 요청 없음 | `GET /api/classes/3/report HTTP/1.1" 200` / `built class report for class 3: 3 distributions, 10 submissions` |
| 14:11:38, 14:14:03, 14:33:45 | access:17605 · 17722 · 18610 / app:1154 · 1165 · 1229 | 재배포 요청 409 3건 **(평소)** | `POST /api/distributions/3/redistribute HTTP/1.1" 409` / `state conflict: 마감된 과제는 재배포할 수 없습니다` |
| 14:19:38 | access:17977 / app:1182 | 배포 7 재배포 200 **(평소)** | `redistributed distribution 7 (assignment 5, class 3) reason=학생A 결석으로 재배포` |
| **14:37:42** | mariadb-slow:15 · 18 · 20 · 21 | **느린 쿼리 시작** (9.81초, Rows_examined 1,284,310). 직전 두 건(09:14, 11:02)은 2초대에 Rows_examined 41,822 이하였다 | `Query_time: 9.812417 … Rows_examined: 1284310` / `from submission s1_0 where s1_0.distribution_id=5` |
| **14:37:52** | mariadb-slow:15 | 위 느린 쿼리 완료 (`# Time: 260916 5:37:52`) **(선후 불확실: 다음 줄과 1초 이내)** | `# Time: 260916  5:37:52` |
| **14:37:52.199** | app:1243 | **첫 커넥션 누수 경고**: Connection@6e8f14d3, 스레드 exec-6, 스택은 `ReportService.buildClassReport` **(선후 불확실: 윗줄과 1초 이내)**. 이후 분별 `37:1 38:2 39:3 40:14 41:13 42:10 43:14 44:11 45:9 46:12 47:9 48:10 49:12 50:11 51:3`, 합계 134건 | `Connection leak detection triggered for org.mariadb.jdbc.Connection@6e8f14d3` |
| **14:38:01** | access:18801 / app:1258 · 1259 | **리포트 요청 재개**(20분 만에 처음). class 3, 200 응답. app은 14:38:01.900에 리포트 생성, .902에 6e8f14d3 반환을 기록 **(선후 불확실: access는 초 단위)**. 이후 리포트 요청 분별 `38:3 39:3 40:10 41:16 42:15 43:19 44:14 45:15 46:19 47:12 48:14 49:16 50:10 51:5` | `"GET /api/classes/3/report HTTP/1.1" 200` / `Previously reported leaked connection … was returned to the pool (unleaked)` |
| 14:38:29 | access:18817 / app:1278 | 배포 5 재배포 200 **(선후 불확실: access ↔ app 1초 이내)** | `redistributed distribution 5 (assignment 3, class 3) reason=null` |
| 14:38:51.641 | app:1280 | 누수 경고에 **두 번째 커넥션** 등장 (2ac19f07, exec-6, 스택 `buildClassReport`) | `Connection leak detection triggered for org.mariadb.jdbc.Connection@2ac19f07` |
| 14:39:50.132 | app:1331 | 누수 경고에 **세 번째 커넥션** 등장 (71d4b8e2, exec-4, 스택 `buildClassReport`) | `Connection leak detection triggered for org.mariadb.jdbc.Connection@71d4b8e2` |
| 14:40:04.730 | app:1351 | 누수 경고에 **네 번째 커넥션** 등장 (3f6c2a91, exec-6, 스택 `buildClassReport`) | `Connection leak detection triggered for org.mariadb.jdbc.Connection@3f6c2a91` |
| 14:40:12.901 | app:1370 | 누수 경고에 **다섯 번째 커넥션** 등장 (5b0e9e0c, exec-5, 스택 `buildClassReport`). 이후 누수 경고에 나오는 커넥션은 이 5개뿐이다 | `Connection leak detection triggered for org.mariadb.jdbc.Connection@5b0e9e0c` |
| **14:40:52** | app:1521 · 1522 · 1523 / access:18918 | **첫 커넥션 풀 타임아웃 · 첫 500** (`/api/units/M6-2/items`) **(선후 불확실: app .319 ↔ access 초 단위)**. 이후 풀 타임아웃 · 500 · `unhandled exception` 이 같은 분별 건수로 나온다: `40:1 41:6 42:2 43:10 44:2 45:8 46:4 47:5 48:4 49:7`, 합계 49건 | `request timed out after 3000ms (total=5, active=5, idle=0, waiting=…` |
| **14:41:06** | access:18933 / nginx-error:6 | **첫 504 · 첫 upstream 타임아웃** (`/api/classes/3/report`, upstream API-1:8080) **(선후 불확실: 두 파일 모두 초 단위, 같은 초)**. 이후 분별 `41:3 42:5 43:5 44:6 45:5 46:6 47:3 48:5 49:8 50:7 51:2`, 합계 55건. upstream은 모두 API-1 | `upstream timed out (110: Connection timed out) while reading response header` |
| 14:41:27.252 | app:1864 | 리포트 API에서 처음으로 `unhandled exception` (이 경로는 14:48:14까지 5건) | `unhandled exception on /api/classes/…/report` |
| **14:42:01** | nginx-error:9 · 10 · 11 / access:18970 | 같은 초에 다음이 이어진다 **(선후 불확실: access ↔ nginx-error, 같은 초)**. ① API-1 타임아웃 ② **API-1 일시 제외** ③ **API-2 연결 거부** ④ access **첫 502** (`/api/classes/2/report`). 일시 제외는 분별 `42:2 43:1 44:4 45:3 46:3 47:1 48:4 49:4 50:4`, 합계 26건(모두 API-1). 연결 거부는 분별 `42:2 43:1 44:1 45:2 46:2 47:1 48:1 49:2 50:2`, 합계 14건(모두 API-2) | `upstream server temporarily disabled while reading response header` / `connect() failed (111: Connection refused) while connecting to upstream` |
| **14:42:04** | nginx-error:12 | **첫 `no live upstreams`**: 가장 많은 오류 유형. access 502는 분별 `42:13 43:2 44:5 45:14 46:16 47:6 48:6 49:8 50:11 51:1`, 합계 82건. `no live upstreams` 는 분별 `42:11 43:1 44:4 45:12 46:14 47:5 48:5 49:6 50:9 51:1`, 합계 68건. 학생용 경로(`/api/units…`, `/api/items…`)에도 나온다 | `no live upstreams while connecting to upstream … /api/units/M6-2/items` |
| 14:42:42 / 14:44:19 / 14:50:22 | app:2174 · 2929 · 5107 / access:18998 · 19078 · 19321 | 재배포 요청 409 · 200 · 409 **(평소 유형)** | `state conflict: 마감된 과제는 재배포할 수 없습니다` / `redistributed distribution 8 …` |
| 14:48:14.538 | app:4361 | 리포트 API의 마지막 `unhandled exception` | `unhandled exception on /api/classes/…/report` |
| 14:49:51 | app:4980 · 4981 / access:19305 | **마지막 풀 타임아웃 · 마지막 500** (`/api/units/M5-3/items`) **(선후 불확실: 같은 초)** | `itembank-pool - Connection is not available, request timed out after 3000ms` |
| 14:50:35.934 · 14:50:53.976 · 14:50:59.001 | app:5128 · 5181 · 5196 | 5b0e9e0c · 3f6c2a91 · 71d4b8e2의 마지막 누수 경고 | `Connection leak detection triggered for org.mariadb.jdbc.Connection@…` |
| 14:51:02 | nginx-error:166 / access:19347 | **마지막 `no live upstreams` · 마지막 502** **(선후 불확실: 같은 초)** | `no live upstreams while connecting to upstream, client: 10.20.x.x` |
| 14:51:06 ~ 14:51:29 | access:19354 · 19364 · 19368 | `/api/classes/3/report` 200 3건 (504 사이) | `"GET /api/classes/3/report HTTP/1.1" 200` |
| 14:51:19.447 / 14:51:37.674 | app:5230 / app:5251 | 2ac19f07의 마지막 누수 경고 / **전체 마지막 누수 경고**(6e8f14d3) | `Connection leak detection triggered for org.mariadb.jdbc.Connection@6e8f14d3` |
| **14:51:47** | access:19381 / nginx-error:168 | **마지막 504 · 마지막 upstream 타임아웃** (`/api/classes/3/report`) **(선후 불확실: 같은 초)** | `upstream timed out (110: Connection timed out) … /api/classes/3/report` |
| 14:51:49.148 | app:5267 | 마지막 누수 커넥션 반환 기록 | `Previously reported leaked connection … was returned to the pool (unleaked)` |
| 14:51:52, 14:52:09 | app:5268 · 5269 / access:19386 · 19401 | 재배포 요청 409 2건 **(평소)** | `state conflict: 마감된 과제는 재배포할 수 없습니다` |
| 14:52 ~ 15:02 | — | 5xx · app ERROR · 누수 경고 · nginx `[error]`/`[warn]` 모두 0건 (파일별 마지막 시각은 1.4 표 참고) | — |
| 14:59:15 · 14:59:52 · 15:01:20 | access:19701 · 19724 · 19781 | `/api/classes/3/report` 200 3건 | `"GET /api/classes/3/report HTTP/1.1" 200` |

### 2.2 가장 많은 오류보다 앞선 평소와 다른 줄

가장 많은 오류는 access 502(82건)와 nginx `no live upstreams`(68건)이다. 둘 다 14:42:01 ~ 14:42:04에 시작한다. 그보다 앞선 줄 중 평소와 다른 것은 아래와 같다.

| 종류 | 시각 | 출처 |
|---|---|---|
| 느린 쿼리 | 14:37:42 시작 ~ 14:37:52 완료. 이날 가장 긴 9.81초, 검사 행 128만 | mariadb-slow:15–21 |
| 경고 | 14:37:52.199 첫 커넥션 누수 경고. 14:40:12까지 서로 다른 커넥션 5개가 차례로 나온다 | app:1243 · 1280 · 1331 · 1351 · 1370 |
| 트래픽 변화 | 14:38:01부터 학급 리포트 요청이 분당 3건, 14:40부터 분당 10 ~ 19건. 평소에는 시간당 2 ~ 11건이다. 같은 시각 전체 요청은 분당 34 ~ 58건으로 평소 범위(평균 39.9, 최대 68)이다 | access:18801 이후 |
| 오류 (앞선 것) | 14:40:52 첫 풀 타임아웃 · 500, 14:41:06 첫 504 · upstream 타임아웃 | app:1522, access:18918 · 18933, nginx-error:6 |

**확인했지만 수집 범위 안에 없는 것**

- 설정 재적재 · 기동 · 종료 메시지: app.log 메시지 유형과 nginx-error 메시지 유형을 모두 세어 봤지만 없다.
- nginx `[warn]` / `[info]`: 범위 안에서는 14:42:01 이후의 `temporarily disabled` 뿐이다. 그 밖의 경고는 09:12 · 13:05 · 13:18로, 모두 수집 범위 밖이다.
- slow query: 범위 안에서는 14:37:42 1건뿐이다.

## 3. 가설과 검증

> 원인 후보를 계층별로 세우고, 각 가설이 **틀렸다고 판단할 조건**과 그 확인 방법을 적는다. 로그로 확인할 수 있는 항목은 실행해서 표의 "검증 결과" 열에 기록했다(2026-10-06). 실행한 명령과 출력은 3.6 절에 있다. 결과는 가설별 유지 · 기각 · 판단 불가(로그 부족)이다. 유지된 가설을 코드 · 설정과 대조한 최종 판정은 3.7 절에 있다.

**읽는 법**

- 지지 근거는 이 문서의 줄(`a-connection-pool.md:줄`)과 그 줄이 가리키는 원본 로그 위치(`app:1243` 처럼)를 함께 적었다. 코드 근거는 `modern/api/src/main/…` 기준 경로다.
- 가장 많은 오류인 access 502(82건) · nginx `no live upstreams`(68건)와 앱의 풀 타임아웃 · 500(49건)은 **증상**이다. 가설로 세우지 않고, 각 가설이 이 증상을 어떻게 설명하는지만 본다(2.2 절 참고).
- 확인 명령은 저장소 루트(`mirae-n-handson/`)에서 실행한다. `L=incident-logs/a-connection-pool` 로 둔다.
- DB 확인은 **로컬 읽기 전용 계정**(`readonly`, `localhost:3306/itembank`)으로만 한다. 운영 DB 결과가 필요한 항목은 DBA · 운영팀에 요청한다고 적었다(운영 DB 접속 명령은 쓰지 않는다).

### 3.1 가설 H1 — 애플리케이션 코드: 리포트 생성이 트랜잭션 안에서 커넥션을 오래 쥔다

| 항목 | 내용 | 검증 결과 (2026-10-06 실행, 명령은 3.6) |
|---|---|---|
| 가설 | `ReportService.buildClassReport` 가 `@Transactional` 안에서 DB 조회를 마친 뒤에도 서명 루프(`sign()`, SHA-256 20만 회)를 돌며 커넥션을 쥐고 있어, 리포트 요청이 겹치면 풀 5개가 모두 리포트에 묶이고 다른 API가 커넥션을 못 얻는다. | **유지** — 누수 134건이 모두 리포트 스택이고 점유 15.5 ~ 21.5초로 14:40:25.8에 풀 5개를 동시에 채웠다. 단 14:37 이전 리포트는 점유 10초 미만이라, 점유가 길어진 계기는 이 가설만으로 설명되지 않는다(반증 ④ 성립). |
| 지지 근거 | · `a-connection-pool.md:154` (app:1243): 첫 누수 경고의 스택이 `ReportService.buildClassReport` ← `ReportController.classReport`<br>· `a-connection-pool.md:157` ~ `160` (app:1280 · 1331 · 1351 · 1370): 누수 경고에 나오는 커넥션이 차례로 5개까지 늘고, 모두 `buildClassReport` 스택. 5는 `maximum-pool-size` 와 같다<br>· `a-connection-pool.md:161` (app:1522): 첫 풀 타임아웃이 `total=5, active=5, idle=0` 이고 대상은 리포트가 아닌 `/api/units/M6-2/items`<br>· `a-connection-pool.md:155` (app:1258 · 1259): 6e8f14d3 획득(누수 경고 14:37:52.2 − 임계 10초 ≈ 14:37:42) ~ 반환(14:38:01.9) 약 20초<br>· 코드: `assignment/ReportService.java:41`(`@Transactional`), `:69-71`(TODO "커넥션을 쥔 채로 돈다"), `:88`(루프) / `resources/application.yml:14-15 · 18`(풀 5, 대기 3초, 누수 임계 10초) | — |
| 반증 조건 | ① 풀 타임아웃이 난 시점에 커넥션을 쥔 스레드 중 `buildClassReport` 가 아닌 스택이 있다(예: `ItemService`, `DistributionService`).<br>② 누수 커넥션의 점유 시간(획득 ~ 반환)이 같은 요청의 DB 쿼리 시간과 거의 같아서 `sign()` 구간이 점유 시간에 기여하지 않는다.<br>③ 사고 당일 운영에 배포된 `ReportService` 에 `sign()` 이 없거나 트랜잭션 밖에 있다.<br>④ 이상 구간 밖(14:10 ~ 14:18 등) 단독 리포트 요청도 같은 코드로 처리됐는데 점유 시간이 짧았다면, 코드만으로는 구간 안의 장기 점유를 설명하지 못한다(다른 가설과의 결합 필요). | ① **불성립**: 누수 스택 134 / 134건이 `buildClassReport` (E1)<br>② **불성립**: 점유 시간 15.5 ~ 21.5초(중앙값 18.9), 리포트 1건의 쿼리 5회는 slow log에 1건만 남음 (E2 · E5 · E6)<br>③ **판단 불가**: 배포 이력 · 운영 이미지 정보가 로그에 없음<br>④ **성립**: 14:37 이전 리포트 50건 동안 누수 경고 0건(점유 < 10초), 14:38 ~ 14:40:12 리포트 7건은 7건 모두 누수 경고(점유 > 10초) (E3 · E4 · E9) |
| 확인 방법 | ①  누수 경고 134건의 스택 첫 앱 프레임 분포: `awk '/Connection leak detection/{f=1;next} f&&/com\.example\./{print $2;f=0}' $L/app.log \| sort \| uniq -c`<br>②  커넥션 ID별 획득 · 반환 짝짓기: `grep -nE 'Connection leak detection\|was returned to the pool' $L/app.log \| grep -oE '^[0-9]+:[^ ]+\|Connection@[0-9a-f]+\|exec-[0-9]+\|unleaked'` → (경고 시각 − 10초)와 반환 시각 차이를 표로 만들고, 같은 스레드의 `built class report` 시각과 비교<br>②  `sign()` 단독 소요 시간: `cd modern/api && ./gradlew test --tests 'com.example.assignment.ReportServiceTest'` 로 기존 테스트를 돌리고, 필요하면 `ReportService.sign` 을 반복 호출하는 측정 테스트를 별도로 작성해 1회 시간을 잰다(운영 CPU와 차이는 감안)<br>③  운영 배포 버전의 `ReportService.java` 를 배포 이력 · 이미지 태그로 확인(저장소 이력은 `git log -p -- modern/api/src/main/java/com/example/assignment/ReportService.java`)<br>④  14:10 ~ 14:18 리포트 요청(app:1148 ~ 1175) 주변에 누수 경고가 없는지: `sed -n '1140,1180p' $L/app.log \| grep -c 'leak detection'` | E1 · E2 · E3 · E4 · E9 실행. ③에 필요한 것: 사고 당일 운영 배포 이력(이미지 태그 · 커밋). ④의 원인을 보려면: access 로그의 `$request_time`, 앱의 요청별 처리 시간 또는 GC · CPU 지표 |

### 3.2 가설 H2 — DB · 쿼리: 제출 조회가 인덱스를 타지 않아 느리다

| 항목 | 내용 | 검증 결과 (2026-10-06 실행, 명령은 3.6) |
|---|---|---|
| 가설 | `submission where distribution_id=?` 조회가 인덱스(`idx_submission_distribution`)를 쓰지 않거나 테이블이 비대해져 전체에 가깝게 스캔하고, 리포트 1건이 쿼리마다 수 초씩 커넥션을 쥐어 풀이 고갈된다. | **기각** (풀 고갈을 이어 간 원인으로서) — 2.04초 쿼리가 기록됐으니 `long_query_time` ≤ 2.04초인데, 점유 15.5초 이상인 133건 동안 slow query가 0건이다. 쿼리 5회가 모두 기준 미만이면 합이 10.2초를 넘지 못한다. 14:37:42의 9.8초 쿼리 1건 자체는 설명되지 않은 채 남는다. |
| 지지 근거 | · `a-connection-pool.md:152` (mariadb-slow:15 · 18 · 20 · 21): 14:37:42 시작, 9.81초, `Rows_sent: 4` 인데 `Rows_examined: 1284310`, 대상은 `distribution_id=5`<br>· `a-connection-pool.md:153`: 완료(14:37:52)와 첫 누수 경고(14:37:52.199)가 1초 이내<br>· `a-connection-pool.md:185`: 이날 가장 긴 slow query, 직전 두 건은 2초대 · 검사 행 41,822 이하<br>· 스키마에는 인덱스가 정의돼 있다(`db/mariadb/init/01-schema.sql:112`) → 정의와 실제 실행 계획이 다를 수 있다 | — |
| 반증 조건 | ① 이상 구간(14:37 ~ 14:51) 안 slow query가 이 1건뿐이고(`a-connection-pool.md:194`), `long_query_time` 이 리포트 점유 시간보다 짧게 설정돼 있다 → 그 뒤 134건의 누수 경고 동안 느린 쿼리가 없었다는 뜻이 된다.<br>② 운영 DB에서 같은 쿼리의 `EXPLAIN` 이 `idx_submission_distribution` 을 쓰고 검사 행이 수십 건 수준이다.<br>③ 누수 커넥션 점유 시간(H1 확인 방법 ②)이 쿼리 시간과 무관하게 일정하다. | ① **성립**: 범위 안 slow query 1건(14:37:42), 누수 경고 134건. 기준값은 로그로 ≤ 2.04초로 추정(mariadb-slow:11의 2.04초 쿼리가 기록됨) — 당일 설정 변경이 없다는 전제 (E5)<br>② **판단 불가**: 운영 EXPLAIN 결과 없음<br>③ **성립**: 점유 시간이 15.5 ~ 21.5초로 고르고, slow query가 있었던 첫 건(19.7초)과 없었던 나머지가 같은 범위 (E2) |
| 확인 방법 | ①  `grep -c '^# Time' $L/mariadb-slow.log` 와 각 `# Time` 값 대조(이미 1.1 표에 3건). 운영 설정은 DBA에게 `SHOW VARIABLES LIKE 'long_query_time'` 결과 요청<br>②  로컬 재현(readonly 계정): `mysql -h 127.0.x.x -P 3306 -u readonly -p itembank -e "SHOW INDEX FROM submission; EXPLAIN SELECT id,distribution_id,score,student_id,submitted_at FROM submission WHERE distribution_id=5 ORDER BY student_id; SELECT COUNT(*) FROM submission;"` — 운영 쪽 같은 결과(실행 계획, 테이블 행 수, 통계 갱신 시각)는 DBA에게 요청<br>③  H1 확인 방법 ②의 표에서 점유 시간과 slow log 시각 비교 | E2 · E5 · E6 실행. ②에 필요한 것: 운영 DB의 `EXPLAIN` · `SHOW INDEX FROM submission` · 테이블 행 수, `long_query_time` 실제 값. 9.8초 쿼리의 이유를 보려면 같은 시각 DB의 `SHOW PROCESSLIST` · InnoDB 상태 · 백업 · 통계 갱신 기록 |

### 3.3 가설 H3 — 트래픽: 학급 리포트 요청이 몰렸다(재시도 · 새로고침 포함)

| 항목 | 내용 | 검증 결과 (2026-10-06 실행, 명령은 3.6) |
|---|---|---|
| 가설 | 교사 화면에서 학급 리포트 요청이 평소(시간당 2 ~ 11건)보다 훨씬 많이(분당 10 ~ 19건) 들어와, 리포트 한 건당 점유 시간이 평소와 같아도 동시 처리 수가 풀 크기(5)를 넘었다. 느린 응답에 대한 새로고침 · 재시도가 요청을 더 늘렸을 수 있다. | **유지** — 리포트 요청이 14시에 179건(다른 시간대 2 ~ 11건)이고, 분당 10건으로 늘어난 14:40이 풀 5개 동시 점유(14:40:25.8) · 첫 오류(14:40:52)보다 앞선다. 다른 날 같은 시간대와의 비교(반증 ②)는 못 했다. |
| 지지 근거 | · `a-connection-pool.md:149`: 14:10 ~ 14:18 리포트 5건 뒤 20분간 리포트 요청 없음<br>· `a-connection-pool.md:155` (access:18801): 14:38:01 리포트 요청 재개, 분별 `38:3 39:3 40:10 41:16 … 51:5`<br>· `a-connection-pool.md:187`: 전체 요청량은 평소 범위인데 리포트만 늘었다<br>· 구간(14:37 ~ 14:51) 리포트 요청 171건의 출처는 교사 IP 4개(10.20.3.x), 대상은 class 1 · 2 · 3 | — |
| 반증 조건 | ① 리포트 요청 증가가 첫 오류(14:40:52 풀 타임아웃, 14:41:06 504) **뒤에만** 나타난다 → 늘어난 요청은 원인이 아니라 결과(재시도)다.<br>② 다른 날 같은 시간대(수업 · 채점 시간)에도 비슷한 리포트 요청량이 있었는데 장애가 없었다.<br>③ 14:38 ~ 14:40의 분당 3건 수준에서 이미 커넥션 5개가 모두 리포트에 잡혔다(누수 경고의 서로 다른 커넥션 5개가 14:40:12에 다 나옴, `a-connection-pool.md:160`) → 요청 "급증" 없이도 고갈된 것이다. | ① **불성립**: 14:40:10 ~ 14:40:50 10초 칸에 200 응답 리포트 10건, 첫 504는 14:41:00 칸 (E7). 다만 재시도 성분도 있다: 같은 IP의 연속 요청 간격 167개 중 5초 미만 43개 (E8)<br>② **판단 불가**: 다른 날 access 로그 없음<br>③ **불성립**: 14:38 ~ 14:40:12 리포트 7건 구간에는 5개 동시 점유가 없었고, 처음 5개가 동시에 잡힌 시각은 14:40:25.8 (E2 · E9) |
| 확인 방법 | ①  10초 단위 리포트 요청 · 상태코드: `awk '$7 ~ /\/report/ && $4 >= "[16/Sep/2026:14:37" && $4 < "[16/Sep/2026:14:43" {print substr($4,14,7)"0", $9}' $L/nginx-access.log \| sort \| uniq -c`<br>①  IP별 요청 간격(일정하면 자동 새로고침): `awk '$7 ~ /\/report/ && $4 >= "[16/Sep/2026:14:37" && $4 < "[16/Sep/2026:14:52" {print $1, substr($4,14,8)}' $L/nginx-access.log \| sort -k1,1 -k2,2`<br>②  다른 날 로그는 이 저장소에 없다 → 운영팀에 9월 다른 평일 14 ~ 15시 `/api/classes/*/report` 분당 건수 요청<br>③  14:38 ~ 14:40:12 사이 리포트 요청 수와 같은 구간 누수 경고 수를 나란히 세기: `grep -nE 'leak detection\|built class report' $L/app.log \| sed -n '/T14:38/,/T14:40:13/p'` | E2 · E7 · E8 · E9 · E10 실행. ②에 필요한 것: 9월 다른 평일 같은 시간대 access 로그, 또는 학급 리포트 사용 일정(수업 · 채점 일정) |

### 3.4 가설 H4 — 인프라: upstream 한 대만 살아 있어 여유가 없었다

| 항목 | 내용 | 검증 결과 (2026-10-06 실행, 명령은 3.6) |
|---|---|---|
| 가설 | nginx upstream 두 대 중 `API-2` 가 장애 이전부터 응답하지 않아(연결 거부) 모든 요청이 `API-1` 한 대로 갔고, `API-1` 의 풀이 고갈돼 타임아웃이 나자 nginx가 `API-1` 을 일시 제외하면서 남은 서버가 없어 학생용 경로까지 502가 났다. | **판단 불가 (로그 부족)** — `API-2` 기록은 14:42:01 ~ 14:50:52 사이 연결 거부 14건뿐이다. nginx error 로그는 성공을 남기지 않고 access 로그에는 `$upstream_addr` 가 없어서, `API-2` 가 14:42 이전에 트래픽을 받았는지 알 수 없다. |
| 지지 근거 | · `a-connection-pool.md:164` (nginx-error:9 · 10 · 11, access:18970): 14:42:01 `API-1` 일시 제외와 **같은 초**에 `API-2` 연결 거부가 처음 기록, 이후 `API-2` 는 연결 거부만 14건<br>· `a-connection-pool.md:165` (nginx-error:12): 3초 뒤 첫 `no live upstreams`, 학생 경로(`/api/units…`, `/api/items…`)도 포함<br>· `a-connection-pool.md:162`: 504의 upstream이 모두 `API-1`<br>· mariadb-slow:2 · 9 · 16: DB 접속 호스트가 3건 모두 `API-1` | — |
| 반증 조건 | ① 14:42 이전에 `API-2` 가 정상 응답한 기록이 있다(즉 `API-2` 는 장애 도중에 내려갔다).<br>② `API-2` 가 nginx 설정상 `backup` 이거나 원래 트래픽을 받지 않는 서버라, 평소에도 `API-1` 한 대로 운영해 왔다 → "여유가 줄었다"는 변화가 없다.<br>③ `API-2` 가 살아 있었더라도 같은 코드 · 같은 풀 설정이라 같은 시각에 고갈됐을 것임이 `API-2` 쪽 앱 로그로 확인된다. | ① **판단 불가**: 하루치 nginx-error에서 `API-2` 14건 모두 14:42:01 ~ 14:50:52, 모두 `Connection refused`. 14:42 이전 기록 없음 (E11)<br>② **판단 불가**: upstream 설정이 저장소 · 로그에 없음<br>③ **판단 불가**: `API-2` 앱 로그 없음. 수집된 app.log에는 기동 · 종료 메시지 0건 (E12) |
| 확인 방법 | ①  `grep -n '<API-2>' $L/nginx-error.log \| head -1` 과 하루치 `grep -c '<API-2>' $L/nginx-error.log`. 단, access 로그는 combined 형식이라 `$upstream_addr` 가 없어 요청별 upstream을 알 수 없다 — 이 한계는 남는다<br>②  nginx upstream 설정(`upstream` 블록의 `backup` · `weight` · `max_fails` · `fail_timeout`)과 `API-2` 의 배포 · 재기동 이력은 저장소에 없다 → 운영팀에 요청<br>③  `API-2` 의 app.log는 수집 대상에 없다(이 파일은 `API-1` 한 대 분량: `grep -c 'Started' $L/app.log` 로 재기동 없음도 함께 확인) → 운영팀에 `API-2` 같은 시간대 로그 요청 | E11 · E12 실행. 판단에 필요했던 로그: access 로그의 `$upstream_addr` · `$upstream_status` · `$upstream_response_time`, nginx `upstream` 블록 설정(`backup` · `max_fails` · `fail_timeout`), `API-2` 의 app.log와 배포 · 재기동 이력 |

### 3.5 정리 — 가설별 증상 설명 범위

판정이 아니라, 각 가설이 어떤 관찰을 설명하려고 하는지만 나란히 둔다.

| 관찰 | H1 코드 | H2 쿼리 | H3 트래픽 | H4 인프라 |
|---|---|---|---|---|
| 누수 경고 스택이 모두 `buildClassReport` | 설명 대상 | 설명 대상 | 설명 대상 | — |
| 누수 커넥션이 정확히 5개, 풀 타임아웃 `active=5` | 설명 대상 | 설명 대상 | 설명 대상 | — |
| 9.81초 slow query 1건 | — | 설명 대상 | — | — |
| 리포트 요청 증가 | 결과일 수 있음 | 결과일 수 있음 | 설명 대상 | — |
| 502 · `no live upstreams` 가 가장 많음 | — | — | — | 설명 대상 |
| **검증 결과 (3.1 ~ 3.4)** | 유지 | 기각 | 유지 | 판단 불가 |

남은 질문: H1 · H3은 유지됐다. 그러나 리포트 1건의 점유 시간이 14:37 이전(10초 미만)과 이후(15.5초 이상) 사이에 왜 달라졌는지, 14:37:42의 9.8초 쿼리가 왜 났는지는 지금 로그로 설명되지 않는다. H4는 3.4 표의 "확인 방법" 행에 적은 로그를 받은 뒤 다시 본다.

### 3.6 실행 기록 (2026-10-06)

저장소 루트에서 `L=incident-logs/a-connection-pool` 로 두고 실행했다. 출력은 그대로 옮겼다. 학생 식별자가 들어간 출력 줄은 없다.

**E1 — 누수 경고 스택의 첫 앱 프레임 (H1 ①)**

```bash
awk '/Connection leak detection/{f=1;next} f&&/com\.example\./{print $2;f=0}' $L/app.log | sort | uniq -c
```
```
    134 com.example.assignment.ReportService$$SpringCGLIB$$0.buildClassReport(<generated>)
```

**E2 — 커넥션 점유 시간과 동시 점유 수 (H1 ② · H2 ③ · H3 ③)**

획득 시각 = 누수 경고 시각 − 10초(`leak-detection-threshold`), 반환 시각 = 같은 커넥션 · 같은 스레드의 `returned to the pool` 시각으로 짝지었다.

```python
import re,datetime as dt,collections
L='incident-logs/a-connection-pool/app.log'
pend={}; holds=[]
for n,line in enumerate(open(L,encoding='utf-8'),1):
    if 'ProxyLeakTask' not in line: continue
    t=dt.datetime.fromisoformat(line.split()[0])
    key=re.search(r'Connection@([0-9a-f]+) on thread ([\w-]+)',line).groups()
    if 'leak detection triggered' in line: pend[key]=(t-dt.timedelta(seconds=10),n)
    elif 'returned to the pool' in line and key in pend:
        a,ln=pend.pop(key); holds.append(((t-a).total_seconds(),ln,n,key,a))
s=sorted(h[0] for h in holds)
print('pairs',len(holds),'unpaired',len(pend))
print('hold(s) min %.1f median %.1f p90 %.1f max %.1f'%(s[0],s[len(s)//2],s[int(len(s)*.9)],s[-1]))
print('5s buckets',sorted(collections.Counter(int(x//5)*5 for x in s).items()))
print('first3',[(round(h[0],1),h[1],h[2]) for h in holds[:3]])
ev=[]
for h in holds: ev+=[(h[4],1),(h[4]+dt.timedelta(seconds=h[0]),-1)]
cur=mx=0;first5=None
for t,d in sorted(ev):
    cur+=d; mx=max(mx,cur)
    if cur>=5 and not first5: first5=t
print('max concurrent',mx,'first time all 5 held',first5)
```
```
pairs 134 unpaired 0
hold(s) min 15.5 median 18.9 p90 21.0 max 21.5
5s buckets [(15, 85), (20, 49)]
first3 [(19.7, 1243, 1259), (17.1, 1261, 1277), (16.1, 1280, 1296)]
max concurrent 5 first time all 5 held 2026-09-16 14:40:25.820000+09:00
```

- 누수 경고 134건이 모두 반환 기록과 짝지어졌다. 점유 시간은 15.5 ~ 21.5초다(15초대 85건, 20초대 49건).
- 첫 건(app:1243 → 1259)은 19.7초이고, 그중 9.8초가 slow query다.

**E3 · E4 — 14:37 이전 리포트 주변의 누수 경고 (H1 ④)**

```bash
sed -n '1140,1180p' $L/app.log | grep -c 'leak detection'
grep -c 'leak detection' $L/app.log
grep -n 'leak detection' $L/app.log | head -1 | cut -c1-40
grep -n 'built class report' $L/app.log | awk -F'[T+]' '$2<"14:37"' | wc -l
```
```
0
134
1243:2026-09-16T14:37:52.199+09:00  WARN
50
```

- 14:37 이전 리포트는 50건이고, 하루 첫 누수 경고는 14:37:52(app:1243)다. 그래서 이전 50건은 점유 시간이 10초 미만이었다.

**E5 — slow query 건수 (H2 ①)**

```bash
grep -c '^# Time' $L/mariadb-slow.log; grep '^# Time' $L/mariadb-slow.log
```
```
3
# Time: 260916  0:14:09
# Time: 260916  2:02:42
# Time: 260916  5:37:52
```

- 범위(05:07 ~ 06:02 UTC) 안에는 5:37:52 한 건이다. 2:02:42 건의 `Query_time` 이 2.04초(mariadb-slow:11)이므로, 기준 `long_query_time` 은 2.04초 이하다.

**E6 — 리포트 1건의 쿼리 수 (H2)**

```bash
grep -o 'built class report for class [0-9]*: [0-9]* distributions, [0-9]* submissions' $L/app.log | sort | uniq -c
```
```
     72 built class report for class 1: 3 distributions, 10 submissions
     35 built class report for class 2: 3 distributions, 10 submissions
     83 built class report for class 3: 3 distributions, 10 submissions
```

- 학급마다 배포가 3건이다. 그래서 리포트 1건의 쿼리는 학급 1 + 배포 목록 1 + 배포별 제출 3 = 5회다(`ReportService.java:43 · 46 · 54`).

**E7 — 10초 단위 리포트 요청과 상태코드 (H3 ①)**

```bash
awk '$7 ~ /\/report/ && $4 >= "[16/Sep/2026:14:37" && $4 < "[16/Sep/2026:14:43" {print substr($4,14,7)"0", $9}' $L/nginx-access.log | sort | uniq -c \
  | awk '{printf "%s %s:%s  ", $2,$3,$1} END{print ""}'
```
```
(출력은 한 줄이다. 읽기 쉽게 분 단위로 줄을 나눴다. 형식: 10초 칸 상태:건수)
14:38:00 200:1  14:38:20 200:1  14:38:50 200:1  14:39:20 200:1  14:39:50 200:2
14:40:10 200:2  14:40:20 200:2  14:40:30 200:2  14:40:40 200:3  14:40:50 200:1
14:41:00 200:3  14:41:00 504:1  14:41:10 200:1  14:41:20 200:3  14:41:20 500:1  14:41:20 504:1
14:41:30 200:1  14:41:40 200:3  14:41:50 200:1  14:41:50 504:1
14:42:00 200:1  14:42:00 502:1  14:42:00 504:1  14:42:10 200:2  14:42:10 502:1  14:42:10 504:1
14:42:20 502:3  14:42:20 504:1  14:42:30 200:1  14:42:30 504:1  14:42:40 200:1  14:42:50 504:1
```

**E8 — IP별 리포트 요청 간격 (H3 ①, 재시도 성분)**

```bash
awk '$7 ~ /\/report/ && $4 >= "[16/Sep/2026:14:37" && $4 < "[16/Sep/2026:14:52" {split(substr($4,14,8),a,":"); print $1, a[1]*3600+a[2]*60+a[3]}' $L/nginx-access.log \
  | sort -k1,1 -k2,2n \
  | awk '$1==p{d=$2-q; b=(d<5?"0-4":d<10?"5-9":d<20?"10-19":d<30?"20-29":"30+"); h[b]++} {p=$1;q=$2} END{for(k in h) print k, h[k]}' | sort
```
```
0-4 43
10-19 51
20-29 40
30+ 23
5-9 10
```

- 교사 IP 4개(10.20.3.x)의 요청 171건이다. 같은 IP에서 이어진 요청 간격은 167개이고, 그중 43개가 5초 미만이다.
IP별 간격 요약:

```bash
awk '$7 ~ /\/report/ && $4 >= "[16/Sep/2026:14:37" && $4 < "[16/Sep/2026:14:52" {split(substr($4,14,8),a,":"); print $1, a[1]*3600+a[2]*60+a[3]}' $L/nginx-access.log \
  | sort -k1,1 -k2,2n \
  | awk '$1==p{d=$2-q; n[$1]++; s[$1]+=d; if(d<mn[$1]||mn[$1]=="")mn[$1]=d; if(d>mx[$1])mx[$1]=d} {p=$1;q=$2} END{for(k in n) printf "%s n=%d avg=%.1fs min=%d max=%d\n",k,n[k]+1,s[k]/n[k],mn[k],mx[k]}'
```
```
교사단말A n=67 avg=10.0s min=0 max=39
교사단말B n=40 avg=20.6s min=1 max=42
교사단말C n=29 avg=19.6s min=1 max=41
교사단말D n=35 avg=20.0s min=0 max=41
```

**E9 — 14:38 ~ 14:40:12 리포트 완료 · 누수 경고 · 요청 (H1 ④ · H3 ③)**

```bash
grep -nE 'leak detection|built class report' $L/app.log | awk -F'[T+]' '$2>="14:38" && $2<"14:40:13"' | awk '{print ($0~/leak/?"leak":"built")}' | sort | uniq -c
awk '$7 ~ /\/report/ && $4 >= "[16/Sep/2026:14:38" && $4 < "[16/Sep/2026:14:40:13" {c++} END{print "report req 14:38-14:40:12:", c}' $L/nginx-access.log
```
```
      7 built
      7 leak
report req 14:38-14:40:12: 7
```

**E10 — 시간대별 리포트 요청 (H3)**

```bash
awk '$7 ~ /\/report/ {c[substr($4,14,2)]++} END{for(h in c) print h, c[h]}' $L/nginx-access.log | sort
```
```
07 2
08 5
09 2
10 11
11 7
12 11
13 8
14 179
15 4
```

**E11 — upstream `API-2` 기록 (H4 ①)**

```bash
grep -n '<API-2>' $L/nginx-error.log | head -1 | cut -c1-120
grep -c '<API-2>' $L/nginx-error.log
grep '<API-2>' $L/nginx-error.log | grep -c 'Connection refused'
grep '<API-2>' $L/nginx-error.log | awk '{print $2}' | sed -n '1p;$p'
head -1 $L/nginx-access.log | cut -c1-120
```
```
11:2026/09/16 14:42:01 [error] 29#29: *225045 connect() failed (111: Connection refused) while connecting to upstream, c
14
14
14:42:01
14:50:52
10.20.x.x - - [16/Sep/2026:06:43:56 +0900] "GET /api/items/45 HTTP/1.1" 404 142 "https://lms.example.com/student/home"
```

- access 로그는 combined 형식이라 upstream 주소 필드가 없다.

**E12 — 앱 기동 · 종료 기록 (H4 ③)**

```bash
grep -c 'Started' $L/app.log; grep -ciE 'Starting|Shutting down|Tomcat started' $L/app.log
```
```
0
0
```

### 3.7 판정

> 3.1 ~ 3.4에서 유지된 H1 · H3을 코드 · 설정과 대조했다. 단서는 로그에 찍힌 클래스 · 메서드 · 설정 키(`ReportService.buildClassReport`, `ReportController.java:22`, `itembank-pool`, `total=5`, 3000ms, 누수 임계)다. 설정값은 파일에서 읽은 값만 쓴다. 벤치마크 · 테스트는 실행하지 않았다.
> 코드 경로는 `modern/api/src/main/` 기준이다(`java/com/example/assignment/…`, `resources/application.yml`).

#### 3.7.1 로그 단서 → 코드 · 설정 대조

| 로그 단서 (출처) | 찾은 코드 · 설정 (파일:줄) | 대조 결과 |
|---|---|---|
| 누수 스택 `ReportController.classReport(ReportController.java:22)` (app:1243 다음 스택, E1 134 / 134) | `assignment/ReportController.java:20-22` `@GetMapping("/{id}/report")` → `reportService.buildClassReport(id)` | 줄 번호까지 일치. 리포트 API가 곧바로 `buildClassReport` 를 부른다 |
| 같은 스택의 커넥션 획득 지점 `JpaTransactionManager.doBegin` ← `TransactionInterceptor` ← `ReportService$$SpringCGLIB$$0.buildClassReport` | `assignment/ReportService.java:41-42` 메서드 전체에 `@Transactional`(readOnly 아님) | 커넥션은 메서드 진입 시 트랜잭션 시작과 함께 잡히고, 메서드가 끝나야 반환된다 |
| 리포트 1건의 쿼리 5회 (E6: 배포 3건) | `ReportService.java:43`(학급), `:46`(배포 목록), `:53-54`(배포마다 제출 조회) / `DistributionRepository.java:13-14`, `SubmissionRepository.java:8` | E6에서 센 쿼리 수와 일치. 5회 모두 slow log 기준(≤ 2.04초) 미만이면 합이 10.2초 미만이다 |
| 점유 시간 15.5 ~ 21.5초 (E2) — 쿼리로 설명되지 않는 최소 5.3초 | `ReportService.java:69-71` DB 작업이 끝난 뒤 트랜잭션 안에서 `sign(payload)` 호출. 주석: "커넥션(트랜잭션)을 쥔 채로 돈다". `:23` `SIGNATURE_ROUNDS = 200_000`, `:85-92` SHA-256을 그 횟수만큼 반복 | 쿼리 이후 메서드 안에 남은 작업은 10건짜리 합산(`:55-67`)과 이 루프뿐이다. 커넥션을 쥔 채 DB와 무관한 CPU 작업을 하는 구간이 코드에 있다 |
| `itembank-pool`, `total=5, active=5`, `request timed out after 3000ms` (app:1522) | `resources/application.yml:10` `pool-name: itembank-pool`, `:14` `maximum-pool-size: 5`, `:15` `connection-timeout: 3000` | 로그 값과 설정 파일 값이 같다. CLAUDE.md:64는 이 값이 "운영 값과 동일"하다고 적고 있다 |
| 누수 경고가 획득 후 10초에 찍힘 (E2의 계산 전제) | `resources/application.yml:18` `leak-detection-threshold: 10000` | E2에서 "경고 시각 − 10초 = 획득 시각"으로 둔 전제와 맞는다 |
| 풀 타임아웃 49건 중 리포트가 아닌 경로 44건(`/api/units…` 33 · `/api/items/` 7 · `/api/distributions/` 4 등), 리포트 5건 | 다른 API도 같은 풀을 쓴다(`application.yml:4-18` 데이터소스 하나). 예: `DistributionService.java:30 · 36 · 49` | 리포트가 커넥션 5개를 쥐면 다른 API가 3초 기다리다 500이 된다. 피해 경로 분포와 맞는다 |
| 리포트 요청 증가 (E7 · E10), referer `lms.example.com/teacher/classes/…/report` (access:18801) | 이 저장소의 `modern/web/src` 에 리포트 호출 · 자동 새로고침 · 재시도 코드가 없다(`grep -rn "report\|setInterval\|retry" modern/web/src` 결과 없음). 서버 쪽 `ReportController.java:20-23` · `ReportService.java` 에 캐시 · 요청 제한이 없다(`modern/api/src/main` 에 `Cacheable` · rate limit 없음) | 요청을 만든 교사 화면 코드는 저장소 밖이다. 서버는 같은 학급 리포트도 매번 처음부터 다시 만든다 |

#### 3.7.2 가설별 판정

| 가설 | 로그 검증 (3.1 ~ 3.4) | 코드 · 설정 대조 | 근거 |
|---|---|---|---|
| H1 앱 코드 | 유지 | **지지한다** | `ReportService.java:41`(메서드 전체 트랜잭션), `:69-71`(트랜잭션 안의 `sign()` 호출과 TODO 주석), `:23 · 88`(20만 회 루프), `application.yml:14 · 15`(풀 5, 대기 3초). 팀 규칙 CLAUDE.md:65("`@Transactional` 메서드 안에서 … DB 작업과 무관한 긴 루프 실행" 금지)와 :40(조회 메서드는 `readOnly = true`)을 모두 어긴다 |
| H3 트래픽 | 유지 | **관련 코드를 찾지 못했다** | 요청을 만든 교사 화면(LMS)은 저장소에 없다. `modern/web/src` 에도 리포트 호출 코드가 없다. 서버 쪽에서 확인한 것은 캐시 · 요청 제한이 없다는 것뿐이다(`ReportController.java:20-23`). 이것은 요청이 늘면 그대로 부하가 된다는 뜻이지, 요청이 왜 늘었는지를 지지하지는 않는다 |

#### 3.7.3 최종 판정

**채택: H1 — `ReportService.buildClassReport` 가 `@Transactional` 안에서 DB 조회를 마친 뒤에도 서명 루프(`sign()`)를 돌며 커넥션을 쥐고 있었다. 그 결과 리포트 요청이 겹치자 크기 5인 풀이 리포트에 모두 묶였고, 다른 API가 3초 대기 끝에 실패했다.**

- 로그: 누수 경고 134건이 모두 이 메서드 스택이다(E1). 쥔 시간은 15.5 ~ 21.5초다(E2). 14:40:25.8에 5개가 모두 잡혔고, 27초 뒤 첫 풀 타임아웃이 났다(app:1522).
- 코드 · 설정: 커넥션을 쥔 채 DB와 무관한 루프를 도는 구간이 있다(`ReportService.java:69-71`). 풀 크기 · 대기 시간이 로그 값과 같다(`application.yml:14-15`).

**채택하지 않은 가설과 사유**

| 가설 | 상태 | 사유 |
|---|---|---|
| H2 DB · 쿼리 | 기각 | 점유 15초 이상인 133건 동안 slow query가 0건이다(E2 · E5, 기준 ≤ 2.04초). 쿼리 5회(`ReportService.java:43 · 46 · 54`)로는 최소 점유 15.5초를 채울 수 없다. 14:37:42의 9.8초 쿼리는 첫 점유(19.7초)를 늘린 단발 사건으로 남는다 |
| H3 트래픽 | 근본 원인으로는 기각, **기여 요인(계기)** 으로 남김 | 리포트 요청 증가(14시 179건)가 동시 점유 5개를 만든 계기인 것은 로그와 맞다(E7 · E10). 그러나 전체 요청량은 평소 범위였고(`a-connection-pool.md:187`), 커넥션을 쥔 것은 모두 리포트 코드였다(E1). 리포트 1건이 커넥션을 DB 작업 시간만큼만 쥐었다면 같은 요청량으로 풀 5개가 동시에 차기 어렵다. 이 저장소에는 요청 증가를 설명할 클라이언트 코드도 없다 |
| H4 인프라 | 판단 불가로 보류, 근본 원인으로 채택하지 않음 | `API-2` 의 평소 상태를 볼 로그가 없다(E11). `API-2` 가 살아 있었다면 502 · `no live upstreams` 는 줄었을 수 있다. 하지만 같은 코드 · 같은 풀 설정이라 풀 고갈 자체를 막는다는 근거는 없다. 이 가설은 피해가 학생 경로 502로 번진 범위를 설명하는 쪽이다 |

**확신 수준: 중간**

높음으로 두지 못하는 이유:

- 로그와 코드가 같은 메커니즘을 가리킨다. 스택은 줄 번호까지 맞고, 풀 크기 5는 누수 커넥션 5개 · `total=5` 와 같고, 점유 시간에서 쿼리로 설명되지 않는 몫이 있다.
- 하지만 같은 코드가 14:37 이전 리포트 50건에서는 커넥션을 10초 미만으로 쥐었다(E3 · E4). 14:38 ~ 14:40의 분당 3건 수준에서도 이미 16 ~ 20초를 쥐었다(E2 · E9). 쥐는 시간이 왜 이렇게 늘었는지(CPU 경합, GC, 다른 프로세스 등)는 코드 · 로그로 설명되지 않는다.
- 저장소의 `ReportService.java` 가 사고 당일 운영 배포본과 같은지 확인하지 못했다. 이 파일의 이력은 초기 커밋 1개(`1260542`)뿐이다.
- 14:37:42의 9.8초 쿼리(`Rows_examined` 128만)의 원인을 모른다.

확신을 높이려면 다음이 필요하다.

- 당일 운영 배포 버전
- 리포트 요청별 처리 시간(access 로그 `$request_time` 또는 앱 타이밍 로그)
- 같은 시각의 CPU · GC 지표

## 4. 원인

> 3.7.3에서 채택한 H1을 인과 순서로 쓴다. 문장 번호(①…)는 5절 근거 표의 번호와 같다. H3(리포트 요청 증가)은 계기, H4(upstream)는 피해 범위를 넓힌 요인으로만 둔다.

**직접 원인** — 커넥션을 쥔 채 DB와 무관한 일을 하는 코드에 동시 요청이 겹쳤다.

- ① `ReportService.buildClassReport` 는 메서드 전체가 `@Transactional` 이라, 리포트 요청이 들어오면 시작할 때 커넥션을 잡고 메서드가 끝나야 놓는다.
- ② 이 메서드는 DB 조회(5회)를 마친 뒤에도 트랜잭션 안에서 SHA-256 20만 회 서명 루프(`sign()`)를 돈다.
- ③ 14:37:42에 9.8초 쿼리가 난 첫 리포트를 시작으로, 14:37 이후 리포트는 커넥션을 15.5 ~ 21.5초씩 쥐었다(134건 모두 리포트 스택).
- ④ 14:38부터 학급 리포트 요청이 다시 들어오고 14:40에는 분당 10건으로 늘었다(계기, H3).
- ⑤ 14:40:25.8에 풀 5개가 모두 리포트에 잡혔다.
- ⑥ 14:40:52부터 같은 풀을 쓰는 다른 API(주로 학생 문항 조회)가 3초 대기 끝에 실패해 500을 냈다(49건, 그중 44건이 리포트 외 경로).
- ⑦ 리포트 요청 자체는 nginx의 upstream 응답 대기 시간을 넘겨 14:41:06부터 504가 됐다(55건, 모두 리포트 경로).

**피해 확대** — 앱 한 대의 풀 고갈이 nginx 전체 실패로 번졌다.

- ⑧ 14:42:01에 nginx가 타임아웃 난 `API-1` 을 일시 제외했고, 같은 초에 `API-2` 는 연결 거부를 냈다.
- ⑨ 남은 upstream이 없어 14:42:04부터 `no live upstreams` 502가 학생 경로까지 났다(502 82건 = `no live upstreams` 68 · `API-2` 연결 거부 14). 가장 많은 오류지만 결과다.
- ⑩ 리포트 요청이 줄고 쥐고 있던 커넥션이 반환되면서 14:51:47 이후 오류가 멎었다.

**배경 원인** — 위 연쇄를 가능하게 한 조건.

- ⑪ 풀은 크기 5, 대기 3초이고, 리포트와 학생용 API가 한 풀을 나눠 쓴다. 느린 경로 하나가 전체를 막을 수 있는 구조다.
- ⑫ 팀 규칙("`@Transactional` 안에서 DB와 무관한 긴 루프 금지", "조회는 `readOnly = true`")을 어긴 코드가 TODO 주석만 남긴 채 배포돼 있었다. 규칙을 기계적으로 검사하는 장치가 없다.
- ⑬ 리포트 API에 캐시 · 요청 제한이 없다. 같은 학급 리포트도 매번 처음부터 만들고, 짧은 간격 재요청(5초 미만 43 / 167)이 그대로 부하가 됐다.
- ⑭ `API-2` 가 언제부터 연결을 거부했는지는 알 수 없다(H4 판단 불가). 다만 사고 시점에는 실제로 응답하는 upstream이 `API-1` 한 대뿐이었다.

**설명되지 않은 부분**: 같은 코드가 14:37 이전 리포트 50건에서는 커넥션을 10초 미만으로 쥐었다. 점유 시간이 왜 늘었는지와 9.8초 쿼리의 원인은 로그로 설명되지 않는다(9절).

## 5. 근거 로그 줄

> 원본 로그는 `incident-logs/a-connection-pool/` 기준 `파일:줄`, 코드는 `modern/api/src/main/` 기준이다. 집계 근거는 3.6 실행 기록(E#)을 가리킨다. 학생 식별자가 든 줄은 인용하지 않았다.

| 문장 | 출처 | 발췌 |
|---|---|---|
| ① | `assignment/ReportService.java:41-42` / app:1253 · 1254 | `@Transactional public ClassReport buildClassReport(…)` / `ReportService$$SpringCGLIB$$0.buildClassReport` ← `ReportController.java:22` |
| ② | `ReportService.java:69-71` · `:23` · `:88-91` | `// DB 작업은 위에서 끝났는데 커넥션(트랜잭션)을 쥔 채로 돈다.` / `SIGNATURE_ROUNDS = 200_000` |
| ③ | mariadb-slow:18 · 21 / app:1243 · 1259 / E1 · E2 | `Query_time: 9.812417 … Rows_examined: 1284310` / `leak detection triggered for …@6e8f14d3 on thread http-nio-8080-exec-6` → 14:38:01.902 `returned to the pool` / E2 `hold(s) min 15.5 … max 21.5`, E1 `134 …buildClassReport` |
| ④ | access:18801 / E7 · E10 | `[16/Sep/2026:14:38:01 +0900] "GET /api/classes/3/report HTTP/1.1" 200` / 14시 179건(다른 시간대 2 ~ 11건) |
| ⑤ | app:1370 / E2 | 다섯 번째 커넥션 `Connection@5b0e9e0c` (14:40:12.901) / `max concurrent 5 first time all 5 held … 14:40:25.820` |
| ⑥ | app:1522 · 1523 / access:18918 | `itembank-pool - Connection is not available, request timed out after 3000ms (total=5, active=5, idle=0, waiting=4)` / `unhandled exception on /api/units/M6-2/items` / `"GET /api/units/M6-2/items HTTP/1.1" 500` |
| ⑦ | nginx-error:6 / access:18933 | `14:41:06 [error] … upstream timed out (110: Connection timed out) while reading response header` / `"GET /api/classes/3/report HTTP/1.1" 504` |
| ⑧ | nginx-error:10 · 11 | `upstream server temporarily disabled while reading response header` / `connect() failed (111: Connection refused) while connecting to upstream` |
| ⑨ | nginx-error:12 / access:18970 | `14:42:04 … no live upstreams while connecting to upstream … "GET /api/units/M6-2/items` / `"GET /api/classes/2/report HTTP/1.1" 502` |
| ⑩ | access:19381 · nginx-error:168 / app:5267 | 마지막 504 · upstream 타임아웃 14:51:47 / 14:51:49.148 `Previously reported leaked connection … returned to the pool (unleaked)` |
| ⑪ | `resources/application.yml:14 · 15` · `:4-18` | `maximum-pool-size: 5`, `connection-timeout: 3000`, 데이터소스 하나 |
| ⑫ | `CLAUDE.md:65` · `:40` / `ReportService.java:41 · 69` | `@Transactional 메서드 안에서 … DB 작업과 무관한 긴 루프 실행` 금지 / `조회 서비스 메서드는 @Transactional(readOnly = true)` |
| ⑬ | `assignment/ReportController.java:20-23` / E8 | 캐시 · 요청 제한 없음(3.7.1) / `0-4 43` (IP별 연속 요청 간격 5초 미만) |
| ⑭ | nginx-error:11 / E11 | `API-2` 기록 14건 모두 `Connection refused`, 14:42:01 ~ 14:50:52 |

## 6. 수정안 (지금 적용)

> 코드는 고치지 않았다. 아래는 제안이다.

**6.1 `sign()` 을 트랜잭션 밖으로 — `modern/api/src/main/java/com/example/assignment/`**

- `ReportService.java:41` 의 `@Transactional` 을 지운다. `buildClassReport` 는 트랜잭션 없이 ⓐ 집계 조회 → ⓑ `sign()` 순서로 호출만 한다.
- DB 조회(`:43-67`)는 **새 빈** `ReportQueryService.loadAggregate(classId)` 로 옮기고 `@Transactional(readOnly = true)` 를 붙인다. 반환은 엔티티가 아닌 record(`ClassReportAggregate`: 학급 id · 이름 · 배포 수 · 제출 수 · 평균 · payload)다.
  - 같은 클래스 안 메서드로만 나누면 Spring 프록시를 거치지 않아(self-invocation) `@Transactional` 이 적용되지 않는다. 그래서 별도 빈으로 뺀다.

```java
public ClassReport buildClassReport(Integer classId) {      // 트랜잭션 없음
    ClassReportAggregate a = reportQueryService.loadAggregate(classId); // readOnly 트랜잭션, 여기서 커넥션 반환
    String signature = sign(a.payload());                    // 커넥션 없이 CPU 작업
    log.info("built class report for class {}: {} distributions, {} submissions", …);
    return new ClassReport(a.classId(), a.className(), a.distributionCount(), a.submissionCount(), a.averageScore(), signature);
}
```

- 테스트: `src/test/java/com/example/assignment/ReportServiceTest.java` 에 `sign()` 시점에 `TransactionSynchronizationManager.isActualTransactionActive()` 가 `false` 인지 확인하는 케이스를 추가한다.
- 기대 효과: 커넥션 점유가 조회 시간(이번 기준 쿼리 5회)으로 줄어 리포트가 몰려도 풀이 리포트에 묶이지 않는다. **리포트 응답 시간 자체는 줄지 않는다.** `sign()` 이 왜 15초 이상 걸렸는지도 이 수정으로 설명되지 않는다(9절).

**6.2 하지 않는 것**

- `application.yml` 의 `maximum-pool-size` · `connection-timeout` 상향: CLAUDE.md:64가 운영 값과 같게 두도록 금지한다. 풀을 키워도 점유 20초 × 동시 요청이 그 수를 넘으면 같은 일이 난다.

**6.3 운영 조치 (코드 외)**

- `API-2` 의 상태 확인 · 복구를 운영팀에 요청한다. 복구 전까지 리포트 사용이 몰리는 시간(이번 14:38 ~)에는 같은 연쇄가 다시 날 수 있다.

## 7. 재발 방지 (구조)

| 대상 | 장치 | 막는 것 |
|---|---|---|
| 규칙 검사 | `.claude/skills/convention-check/SKILL.md` 에 CLAUDE.md:65 · :40 규칙(트랜잭션 안 해시 · 외부 호출 · 긴 루프 금지, 조회 메서드 `readOnly = true`)을 추가한다. 지금은 `@Transactional` 관련 항목이 없다. 같은 규칙을 ArchUnit 테스트로도 고정한다(의존성 추가라 사전 승인 필요) | ⑫ 규칙 위반 코드가 TODO만 남긴 채 배포되는 것 |
| 테스트 | test 프로필(`src/test/resources/application-test.yml`, 지금은 `hikari.maximum-pool-size: 2` 만 있다)에 `leak-detection-threshold` 를 짧게(예: 2초) 추가하고, 누수 경고가 찍히면 실패하는 통합 테스트를 `ReportServiceTest` 에 둔다 | 커넥션을 오래 쥐는 코드의 회귀 |
| 리포트 경로 격리 | 학급별 리포트 결과 캐시(짧은 TTL)와 리포트 동시 실행 수 제한(예: 풀 크기보다 작은 2)을 둔다. 서명이 무거우면 비동기 생성 후 조회로 바꾼다 | ⑪ · ⑬ 느린 경로 하나가 공유 풀 전체를 차지하는 것, 새로고침 · 재요청 증폭 |
| 클라이언트 | 교사 화면(LMS, 이 저장소 밖)의 리포트 재요청에 디바운스 · 응답 대기 중 재요청 막기를 담당 팀에 요청 | ⑬ |
| 인프라 | upstream 2대가 모두 트래픽을 받는지 배포 · 점검 체크리스트에 넣고, 한 대가 빠져도 견디는 용량을 기준으로 둔다 | ⑭ 한 대 장애가 곧바로 `no live upstreams` 가 되는 것 |

## 8. 모니터링 항목

> 시각은 1.3 분 단위 표와 3.6 실행 결과로 계산한 "이번에 울렸을 시각"이다. 평소 기준은 이날 하루치(511분)뿐이라 다른 날 데이터로 다시 맞춰야 한다. 첫 사용자 오류(500)는 14:40:52다.

| 순서 | 지표 | 임계값 (평소) | 이번에 울렸을 시각 | 비고 |
|---:|---|---|---|---|
| 1 | Hikari 누수 경고(`ProxyLeakTask` WARN) 건수 | 1분에 1건 이상 → 경고 (평소 0) | **14:37:52** (app:1243) | 첫 오류보다 3분 빠르다. 로그 기반이라 지금 바로 걸 수 있다 |
| 2 | slow query `Query_time` | 5초 이상 → 경고 (평소 최대 2.32초) | **14:37:52** (mariadb-slow:15, 완료 시각) | 단발 신호다. 1과 같이 오면 심각으로 올린다 |
| 3 | `/api/classes/*/report` 요청 수 | 분당 10건 이상 → 경고 (평소 시간당 2 ~ 11건) | **14:40** (분별 `40:10`, 집계 끝 14:41:00) | 분당 5건 기준이면 같은 14:40 |
| 4 | Hikari `active == maximum-pool-size` 지속 · `pending > 0` | 10초 이상 지속 → 심각 | **14:40:35 무렵** (5개 동시 점유 14:40:25.8, E2) | 풀 지표 수집이 지금은 없다(9절). 정확한 지속 여부는 E2의 추정에 기댄다 |
| 5 | 커넥션 획득 타임아웃(`Connection is not available`) | 1건 이상 → 심각 (평소 0) | **14:40:52** (app:1522) | 이미 사용자 오류다 |
| 6 | nginx 5xx | 분당 5건 이상 → 심각 (평소 0) | **14:41** (9건, 집계 끝 14:42:00) | 1건 기준이면 14:40:52 |
| 7 | nginx `upstream timed out` | 1건 이상 → 심각 (평소 0) | **14:41:06** (nginx-error:6) | |
| 8 | 살아 있는 upstream 수 < 2 / `no live upstreams` | 각각 경고 / 1건 이상 → 긴급 | **14:42:01** (nginx-error:10 · 11) / **14:42:04** (nginx-error:12) | 마지막 방어선이라 늦다. 살아 있는 upstream 수를 능동 점검하면 `API-2` 이상을 더 일찍 알았을 수 있다(시각은 판단 불가) |

- 1 · 2를 경고로 받았다면 첫 사용자 오류 3분 전, 5xx 기준(6)보다 약 4분 먼저 알았다.

## 9. 확인하지 못한 것

**로그가 없어 판단하지 못한 것**

| 항목 | 막힌 이유 | 필요한 것 |
|---|---|---|
| 리포트 점유 시간이 14:37 전후로 10초 미만 → 15.5초 이상으로 는 이유 | CPU · GC · 스레드 지표 없음, 요청별 처리 시간 없음 | 같은 시각 `API-1` 의 CPU · GC 로그 |
| 14:37:42 9.8초 쿼리(`Rows_examined` 128만)의 원인 | 운영 실행 계획 · 통계 · 동시 작업 기록 없음 | 운영 `EXPLAIN` · `SHOW INDEX FROM submission`, 같은 시각 `SHOW PROCESSLIST` · 백업 · 통계 갱신 기록(DBA 요청) |
| 운영 배포본이 저장소 `ReportService.java` 와 같은지 | 배포 이력 없음, 파일 이력은 초기 커밋 1개 | 당일 이미지 태그 · 커밋 |
| `API-2` 가 언제부터 연결을 거부했는지, `backup` 여부 | access 로그에 upstream 필드 없음, nginx 설정 없음 | nginx `upstream` 블록(`backup` · `max_fails` · `fail_timeout`), `API-2` 의 app.log · 재기동 이력 |
| 리포트 요청이 14:38부터 는 이유 | 교사 화면 코드가 저장소 밖, 다른 날 로그 없음 | 다른 평일 같은 시간대 access 로그, 수업 · 채점 일정 |
| 504가 난 nginx 대기 시간, 실제 `long_query_time` | 설정이 저장소에 없음 | nginx `proxy_read_timeout`, DB `long_query_time` |
| 이번에 기존 경보가 있었는지 · 울렸는지 | 모니터링 기록이 수집 대상에 없음 | 경보 발송 이력 |

**다음을 위해 남겨야 할 로그**

- nginx access 로그 형식에 `$request_time` · `$upstream_addr` · `$upstream_status` · `$upstream_response_time` 추가.
- 앱: 요청별 처리 시간과 트랜잭션(커넥션 점유) 시간을 남기고, 요청 ID(`X-Request-ID`)를 nginx와 앱 로그에 같이 찍어 파일 간 대조를 줄 단위로 한다.
- Hikari 풀 지표(active · idle · pending · 획득 대기 시간)와 JVM GC 로그. 지표 수집 의존성 추가는 사전 승인 대상이다.
- upstream 서버별 app.log를 모두 수집(이번에는 `API-1` 분량뿐), 배포 · 재기동 이벤트를 시각과 함께 기록.
- slow log에는 실행 계획 정보를 같이 남기도록 DBA와 협의.

마스킹 적용: IP 59건 · 이메일 0건 · 학생 식별자 1건 · 토큰 · 세션 ID · API 키 0건 · 비밀번호 · 접속 문자열 0건
