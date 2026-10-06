# RCA 리포트: b-deploy-5xx (배포 후 특정 API 5xx 증가)

- 대상 로그: `incident-logs/b-deploy-5xx/` (`app.log`, `nginx-access.log`, `deploy-history.md`)
- 작성 범위: **1. 수집 범위 · 2. 타임라인 · 3. 가설과 검증(가설 표까지)**. 요청에 따라 반증 확인 · 코드 대조(4절)와 요약 · 원인 · 수정안 · 재발 방지 · 모니터링(5절 항목)은 이번에 작성하지 않았다.
- 명령은 저장소 루트에서 `L=incident-logs/b-deploy-5xx` 로 두고 실행했다. 명령과 출력은 끝의 "실행 명령 기록(E1…)"에 있다.
- 표기: 교사 단말 IP 는 역할 별칭(`교사단말A`, `교사단말B`), 그 밖의 클라이언트 IP 는 `10.20.x.x` 로 가렸다. 학생 식별자는 `학생A` … 로 가렸다.

---

## 1. 수집 범위

### 1.1 파일별 개요 (E1, E2)

| 파일 | 줄 수 | 기록 기간 (KST) | 시각 형식 | 타임존 판단 |
|---|---|---|---|---|
| `app.log` (Spring Boot) | 2,938 (시각으로 시작하는 줄 1,275 · 스택 트레이스 등 1,663) | 2026-09-17 09:00:04 ~ 14:39:57 | ISO-8601 `2026-09-17T09:00:04.878+09:00` | 오프셋 `+09:00` 명시 → KST 그대로 |
| `nginx-access.log` (combined) | 20,365 | 2026-09-17 09:00:00 ~ 14:39:58 | `[17/Sep/2026:09:00:00 +0900]` | 오프셋 `+0900` 명시 → KST 그대로 |
| `deploy-history.md` | 39 | v1.3.9(09-01) ~ v1.4.2(09-17) | `2026-09-17 13:40:05` | 본문에 "시각은 모두 KST" 명시 |

- 세 파일 모두 타임존이 명시돼 있어 다른 파일과 시각을 맞춰 추정할 필요가 없었다. 교차 확인: 재기동 구간 `app.log:1240`(13:41:48 graceful shutdown 시작) ~ `app.log:1264`(13:42:00 기동 완료) 사이에 `nginx-access.log:16865~16872` 의 502 8건(13:41:51~59)이 들어가 두 로그의 시계가 초 단위로 맞는다.

### 1.2 집계 기준

- `app.log`: `^2026-09-17T` 로 시작하는 줄만 1건으로 센다. 스택 트레이스(`at …`, `Caused by`, `... N more`)는 세지 않는다.
- `nginx-access.log`: 한 줄 = 요청 1건. 상태 코드는 9번째 필드.
- 배포 전/후 경계는 배포 시작(13:40:05)이 아니라 **실제 프로세스 교체 시점**으로 둔다: 13:41:48 이전 = v1.4.2 이전 프로세스, 13:42:00 이후 = v1.4.2(`app.log:1245` "Starting ItemBankApplication v1.4.2"). 13:41:20 의 재배포 200(`nginx-access.log:16829`)은 이전 프로세스가 처리했다.

### 1.3 평소 수준과 이상 구간 (E3, E4, E5)

| 지표 | 평소 수준 | 이상 구간 |
|---|---|---|
| nginx 분당 요청 | 40 ~ 82건(중앙값 59, 340분) | 이상 없음. 시간당 09시 3,625 · 10시 3,621 · 11시 3,762 · 12시 3,215 · 13시 3,678 · 14시 2,464 (14시는 14:39:58 까지만 있는 **부분 시간**이라 적다) |
| nginx 5xx | 분당 0건 | ① 13:41 502 ×8(13:41:51~59) ② 13:46 ~ 14:34 500 ×39(전부 `POST /api/distributions/{42,43}/redistribute`) ③ 배포 전 산발 500 ×7(09:52 ×3, 10:31 ×1, 11:17 ×1, 11:18 ×2, 전부 `/api/distributions/41…`) |
| app ERROR | 0건 | 위 500 과 1:1(총 46건 = 배포 전 7 + 배포 후 39). 예외 종류는 46건 모두 `JpaObjectRetrievalFailureException … Assignment … identifier value 9` 한 가지 |
| 재배포 200 응답 크기 | 170 ~ 200 바이트(13:41:48 이전) | 640 ~ 910 바이트(13:42:00 이후) |

**이상 신호에서 뺀 평소 신호** (배포 전후로 비율이 같거나 하루 종일 나온다)

| 신호 | 건수 | 뺀 이유 |
|---|---|---|
| 409 `state conflict: 마감된 과제는 재배포할 수 없습니다` (distributionId 1·2·3) | 35 (재기동 전 31 · 후 4) | 09:06 부터 하루 종일 같은 비율, WARN |
| `no handler for …` (favicon · robots.txt · `/api/unit` · `/api/item/12` 등) | 626 | 하루 종일, INFO, 404 |
| `not found: 문항/배포/단원이 없습니다` | 343 | 하루 종일, INFO, 404 |
| `bad request: … For input string: "NaN"/"undefined"/"null"` | 29 | 하루 종일, INFO, 400 |

### 1.4 수집 범위 확정

- **주 구간: 2026-09-17 13:35:00 ~ 14:39:58(로그 끝)** — 배포 시작(13:40:05) 5분 앞 ~ 마지막 500(14:34:45) 뒤 로그 끝까지.
- **부 구간(시그니처로 포함)**: 같은 예외(`Assignment` identifier `9`)가 처음 나온 09:52:17 ~ 11:18:02 의 배포 전 500 7건.
- 주 구간 앞의 평소 구간(09:00 ~ 13:35)은 비교 기준으로만 쓴다.

---

## 2. 타임라인 (KST)

| # | 시각 | 사건 | 출처 |
|---|---|---|---|
| T1 | 09:52:17 | 처음 나온 500. 교사단말A `POST /api/distributions/41/redistribute` → 500 (176 바이트) | `nginx-access.log:3150` |
| T2 | 09:52:17.335 | 같은 요청의 ERROR `unhandled exception on /api/distributions/41/redistribute`, `Entity com.example.assignment.Assignment with identifier value 9 does not exist`, 호출 경로 `DistributionService.loadOrThrow(DistributionService.java:65)` ← `DistributionService.redistribute(…:51)` ← `DistributionController.redistribute(…:38)` | `app.log:194~195`, `app.log:205~206`, `app.log:214` |
| T3 | 09:52 ×3 · 11:17 ×1 · 11:18 ×2 | 같은 요청(41 재배포) 반복 500, 모두 교사단말A. 예외 · 호출 경로 동일 | `nginx-access.log:3162`, `:3174`, `:8442`, `:8466`, `:8468` / `app.log:232`, `:270`, `:655`, `:695`, `:733` |
| T4 | 10:31:05 | 교사단말A `GET /api/distributions/41` → 500. 같은 예외, 경로 `DistributionService.getDistribution(…:32)` | `nginx-access.log:5563`, `app.log:451` |
| T5 | 13:13:27 | 배포 전 마지막 학급 1(배포 42·43) 재배포 묶음의 200 (13:13 ×4) | `nginx-access.log:15002` |
| T6 | 13:40:05 | v1.4.2 배포 시작. 변경: 재배포 응답에 학급 배포 이력 포함, `DistributionController.redistribute` 에서 재배포 후 `DistributionService.listByClass(classId)` 호출 추가. 방식: 단일 호스트(API-1) 재기동, 무중단 아님 | `deploy-history.md:7`, `:15`, `:16`, `:18` |
| T7 | 13:41:20 | 이전 프로세스의 마지막 학급 1 재배포 200 (교사단말A, 배포 43, 183 바이트) | `nginx-access.log:16829` |
| T8 | 13:41:48.214 | `Commencing graceful shutdown` | `app.log:1240` |
| T9 | 13:41:50.144 | `Graceful shutdown complete` (선후 불확실: T11 과 2초 이내) | `app.log:1241` |
| T10 | 13:41:50.224 | Hikari 풀 `Shutdown completed` (선후 불확실: T11 과 2초 이내) | `app.log:1244` |
| T11 | 13:41:51 ~ 59 | 502 ×8 (첫 줄: `GET /api/units/M5-2/items?student=학생A` 502 157 바이트). 경로 · 단말 다양 | `nginx-access.log:16865` (~ `:16872`) |
| T12 | 13:41:55.902 | `Starting ItemBankApplication v1.4.2` | `app.log:1245` |
| T13 | 13:42:00.304 | `Started ItemBankApplication in 4.921 seconds` | `app.log:1264` |
| T14 | 13:42:10 | 재기동 후 첫 재배포 200 (교사단말B, 배포 7, **802 바이트**) | `nginx-access.log:16884` |
| T15 | 13:42:31 | 배포 완료 기록 | `deploy-history.md:7` |
| T16 | 13:46:38.107 | INFO `redistributed distribution 42 (assignment 4, class 1) reason=보강 수업 후 재배포` | `app.log:1274` |
| T17 | 13:46:38 | 같은 요청이 500. ERROR `unhandled exception on /api/distributions/42/redistribute`, 같은 예외(identifier `9`), 호출 경로 `DistributionService.listByClass(DistributionService.java:38)` ← `DistributionController.redistribute(DistributionController.java:40)` | `nginx-access.log:17155`, `app.log:1275~1276`, `app.log:1286`, `app.log:1294` |
| T18 | 13:46 ~ 14:34 | 같은 패턴(INFO `redistributed distribution 42/43 … class 1` 직후 ERROR) 반복, 모두 교사단말A. 분별 500: 13:46 2 · 13:47 1 · 13:50 4 · 13:52 1 · 13:53 1 · 13:55 3 · 13:58 2 · 14:02 2 · 14:03 4 · 14:06 1 · 14:11 3 · 14:15 1 · 14:16 2 · 14:22 3 · 14:27 2 · 14:28 1 · 14:31 2 · 14:32 1 · 14:34 3 (합 39, 배포 42 ×19 · 43 ×20) | `nginx-access.log:17155` ~ `:20084`, `app.log:1275` ~ `:2887` |
| T19 | 13:50:00 · 13:56:43 | 같은 교사단말A 의 학급 2 재배포(배포 8 · 44)는 200 (908 · 640 바이트) | `nginx-access.log:17353`, `:17734` |
| T20 | 13:51:08 · 14:21:02 · 14:22:46 | 교사단말B 의 학급 3 재배포(배포 7 · 5)는 200 | `nginx-access.log:17424`, `:19238`, `:19347` |
| T21 | 14:34:45 | 마지막 500 (배포 43) | `nginx-access.log:20084`, `app.log:2887` |
| T22 | 14:39:58 | 로그 끝 | `nginx-access.log:20365` |

### 2.1 가장 많은 오류(재배포 500)보다 앞선, 평소와 다른 줄

| 시각 | 줄 | 출처 |
|---|---|---|
| 09:52:17 | 배포 41 재배포 500, `Assignment` identifier `9` 없음 (그날 처음 나온 500 · 처음 등장한 배포 id 41) | `nginx-access.log:3150`, `app.log:194` |
| 10:31:05 | 배포 41 단건 조회 500, 같은 예외 | `nginx-access.log:5563`, `app.log:451` |
| 11:17:40 ~ 11:18:02 | 배포 41 재배포 500 ×3 | `nginx-access.log:8442`, `:8466`, `:8468` |
| 13:40:05 | v1.4.2 배포 시작(재배포 후 `listByClass` 호출 추가) | `deploy-history.md:7`, `:16` |
| 13:41:51 ~ 59 | 502 ×8 | `nginx-access.log:16865` ~ `:16872` |
| 13:42:00 vs 13:42:31 | 앱 기동 완료 시각과 배포 이력의 "배포 완료" 시각이 31초 다름 | `app.log:1264`, `deploy-history.md:7` |
| 13:42:10 | 재배포 200 응답 크기 170~200 → 640~910 바이트로 변화(첫 줄) | `nginx-access.log:16884` |
| 09:00 ~ 14:39 | 트래픽: 분당 40~82건 범위를 벗어난 분 없음 | E3 |
| 09:00 ~ 14:39 | 느린 쿼리 · 커넥션 풀 경고: `app.log` 에 Hikari 경고 없음(Hikari 줄 5건은 모두 종료 · 기동). 이번 묶음에 DB 슬로 로그 없음 | E6 |

---

## 3. 가설과 검증

**증상**: v1.4.2 재기동(13:42:00) 이후 `POST /api/distributions/{42,43}/redistribute` 가 500 39건(교사단말A). 같은 시간대 학급 2 · 3 재배포는 200. 별개로 재기동 중 502 8건.

| 가설 | 계층 | 증상을 어떻게 설명하나 | 지지 근거 (리포트 줄 + 원본) | 반증 조건 (틀렸다고 판단할 관찰) | 확인 방법 |
|---|---|---|---|---|---|
| **H1. v1.4.2 가 재배포 응답에 학급 배포 목록 조회를 붙여, 학급 1 목록에 섞인 깨진 배포(41)를 매 재배포마다 읽게 됐다** | 앱 코드 (배포 변경) | 재배포 자체는 끝난 뒤 `listByClass(class 1)` 에서 `Assignment 9` 를 못 찾아 500. 학급 1 에만 해당 | T6(`deploy-history.md:16`), T16 · T17(`app.log:1274` INFO 직후 `app.log:1275` ERROR, 경로 `app.log:1286` `listByClass(…:38)` ← `app.log:1294` `Controller.redistribute(…:40)`), 재기동 후 ERROR 39건 전부 `listByClass` 경유(E7), 재기동 후 학급 1 재배포 200 = 0건 · 학급 2 · 3 = 6건 모두 200(E8), 200 응답 크기 증가(T14) | (a) 재기동 후 학급 2 · 3 재배포에서 같은 500 이 나온다 (b) 재기동 후 학급 1 재배포가 200 을 받은 줄이 있다 (c) 재기동 후 ERROR 중 `listByClass` 를 거치지 않은 재배포 500 이 있다 (d) 배포 41 이 학급 1 소속이 아니다 | E7 · E8 의 집계 재실행 · `grep -c 'DistributionService.listByClass' $L/app.log` · 배포 41 의 학급은 로그에 없으므로 로컬 읽기 전용 DB 에서 `SELECT id, class_id, assignment_id FROM <배포 테이블> WHERE id = 41` (테이블 · 컬럼명은 4절에서 스키마로 확인) · 코드에서 `listByClass` 조회의 `Assignment` 연관 로딩 확인(4절) |
| **H2. 배포 41 이 존재하지 않는 과제 9 를 가리키는 데이터 결함이 배포 전부터 있었다** | DB 데이터 | 배포 41 을 읽는 모든 경로(단건 · 재배포 · 학급 목록)가 `FetchNotFoundException` 으로 500. 배포 후에는 학급 목록 조회가 41 을 끌어와 범위가 넓어짐 | T1 ~ T4(`app.log:194`, `:451` — 배포 전, 경로 `loadOrThrow` · `getDistribution`), 46건 전부 identifier `9`(E6: `identifier value 9` 92회 = 예외 줄 + `Caused by` 줄, 다른 값 없음), 배포 41 성공 로그 0건(`redistributed distribution 41` 없음) | (a) 과제 9 가 DB 에 있다 (b) 배포 41 요청이 한 번이라도 200 을 받았다 (c) 배포 후 500 의 identifier 가 9 가 아니다 (d) 학급 1 리포트(`built class report for class 1: 3 distributions`, `app.log:6` ~ `:1191`)가 배포 41 을 포함하고도 정상 생성됐다면 "41 을 읽으면 항상 실패"라는 설명과 충돌 | 로컬 읽기 전용 계정으로 `mysql -h localhost -P 3306 -u readonly -p<비밀번호> itembank -e "SELECT … WHERE id = 9"`(과제) · `… WHERE id = 41`(배포) · 과제 9 를 참조하는 배포 전체 조회. 운영 DB 결과는 직접 조회하지 않고 요청 항목으로 적는다 |
| **H3. 무중단이 아닌 단일 호스트 재기동이 5xx 를 만들었다** | 인프라 · 배포 방식 | 재기동 공백 동안 upstream 이 없어 502. 500 39건은 설명하지 못함(별도 증상) | T6(`deploy-history.md:18` "무중단 아님"), T8 ~ T13(`app.log:1240` ~ `:1264`), T11(`nginx-access.log:16865` ~ `:16872`, 502 ×8 이 shutdown ~ Started 사이에만 있음) | (a) 13:42:00 이후 502 가 있다 (b) 500 이 재기동 공백과 겹친다 (c) 재기동 없이도 같은 500 이 난다 | `awk '$9==502' $L/nginx-access.log` 시각 분포 · 재기동 전후 5xx 경로 비교(E4) |
| **H4. 한 교사 단말의 반복 재시도가 건수를 키웠다** | 트래픽 · 클라이언트 | 1회 실패가 10 ~ 20초 간격 3회 묶음 재시도로 이어져 500 건수가 늘어남. 500 자체의 발생은 설명하지 못함 | T3 · T18(500 46건 모두 교사단말A, 3건 묶음: 예 `nginx-access.log:17155`, `:17170`, `:17181`), 재시도마다 INFO `redistributed …`가 먼저 기록됨(재기동 후 ERROR 39건 모두 직전 줄이 재배포 INFO, E7) | (a) 500 을 받은 단말이 여럿이다 (b) 500 사이 간격이 사람 조작이 아닌 자동 재시도 간격(ms 단위)이 아니라면 클라이언트 자동 재시도 가설만 기각 (c) 분당 요청이 평소 범위(40~82)를 벗어난 분이 있다면 트래픽 급증 쪽으로 재검토 | `awk '$9==500{print $1}' $L/nginx-access.log \| sort \| uniq -c` · 500 간 간격 계산 · 재배포 1건당 DB 쓰기 반영 여부는 읽기 전용 DB 에서 학급 1 배포의 재배포 이력 건수 확인(4절) |

- H1 과 H2 는 서로 배타적이지 않다(배경 데이터 결함 + 배포 변경으로 노출 범위 확대일 수 있음). 판정은 4절에서 반증 조건을 하나씩 확인한 뒤 한다.
- 가장 많은 오류(`JpaObjectRetrievalFailureException`)는 증상으로만 두었다.

> 4절(반증과 판정 · 코드 대조) 이후는 요청에 따라 작성하지 않았다.

---

## 실행 명령 기록

출력의 IP · 학생 식별자는 마스킹 규칙에 따라 가렸다.

**E1** 파일별 줄 수

```bash
wc -l $L/*
```
```
   2938 incident-logs/b-deploy-5xx/app.log
     39 incident-logs/b-deploy-5xx/deploy-history.md
  20365 incident-logs/b-deploy-5xx/nginx-access.log
```

**E2** 시각 형식 · 기간 · 스택 줄 수

```bash
head -1 $L/app.log; tail -1 $L/app.log; head -1 $L/nginx-access.log; tail -1 $L/nginx-access.log
grep -vc '^2026-09-17T' $L/app.log
grep -oE '^2026-\S+\s+[A-Z]+' $L/app.log | awk '{print $2}' | sort | uniq -c
```
```
2026-09-17T09:00:04.878+09:00  INFO 1 --- [item-bank-api] [nio-8080-exec-5] c.example.common.GlobalExceptionHandler  : not found: 문항이 없습니다: id=47 (/api/items/47)
2026-09-17T14:39:57.905+09:00  INFO 1 --- [item-bank-api] [io-8080-exec-10] c.example.common.GlobalExceptionHandler  : no handler for /favicon.ico
10.20.x.x - - [17/Sep/2026:09:00:00 +0900] "GET /api/units/M5-3/items HTTP/1.1" 200 1465 ...
10.20.x.x - - [17/Sep/2026:14:39:58 +0900] "GET /api/units HTTP/1.1" 200 613 ...
1663
     46 ERROR
   1194 INFO
     35 WARN
```

**E3** nginx 분당 건수 분포 · 시간당 건수

```bash
awk '{t=substr($4,14,5); n[t]++} END{for(k in n) print n[k]}' $L/nginx-access.log | sort -n | awk '{a[NR]=$1}END{print "min="a[1]" median="a[int(NR/2)]" max="a[NR]" n="NR}'
awk '{print substr($4,14,2)}' $L/nginx-access.log | uniq -c
```
```
min=40 median=59 max=82 n=340
   3625 09    3621 10    3762 11    3215 12    3678 13    2464 14
```

**E4** 상태 코드 · 5xx 경로

```bash
awk '{print $9}' $L/nginx-access.log | sort | uniq -c
awk '$9>=500{print NR": "$1,$4,$6,$7,$9,$10}' $L/nginx-access.log
```
```
  19278 200
     29 400
    969 404
     35 409
     46 500
      8 502
3150: 교사단말A [17/Sep/2026:09:52:17 "POST /api/distributions/41/redistribute 500 176
3162: 교사단말A [17/Sep/2026:09:52:30 "POST /api/distributions/41/redistribute 500 176
3174: 교사단말A [17/Sep/2026:09:52:41 "POST /api/distributions/41/redistribute 500 176
5563: 교사단말A [17/Sep/2026:10:31:05 "GET /api/distributions/41 500 176
8442: 교사단말A [17/Sep/2026:11:17:40 "POST /api/distributions/41/redistribute 500 176
8466: 교사단말A [17/Sep/2026:11:18:01 "POST /api/distributions/41/redistribute 500 176
8468: 교사단말A [17/Sep/2026:11:18:02 "POST /api/distributions/41/redistribute 500 176
16865: 10.20.x.x [17/Sep/2026:13:41:51 "GET /api/units/M5-2/items?student=학생A 502 157
16866: 10.20.x.x [17/Sep/2026:13:41:52 "GET / 502 157
16867: 10.20.x.x [17/Sep/2026:13:41:53 "GET /api/distributions/8?student=학생B 502 157
16868: 10.20.x.x [17/Sep/2026:13:41:53 "GET /api/distributions/5 502 157
16869: 10.20.x.x [17/Sep/2026:13:41:53 "GET /api/units 502 157
16870: 10.20.x.x [17/Sep/2026:13:41:55 "GET /api/items/5 502 157
16871: 10.20.x.x [17/Sep/2026:13:41:58 "GET /api/units/M6-1/items 502 157
16872: 10.20.x.x [17/Sep/2026:13:41:59 "GET /api/units/M6-1/items?student=학생C 502 157
17155: 교사단말A [17/Sep/2026:13:46:38 "POST /api/distributions/42/redistribute 500 176
... (13:46 ~ 14:34, 39줄 모두 교사단말A, POST /api/distributions/42 또는 43/redistribute 500 176)
20084: 교사단말A [17/Sep/2026:14:34:45 "POST /api/distributions/43/redistribute 500 176
```

**E5** 재배포 요청: 재기동 전후 · 학급 · 상태별 (학급은 `app.log` INFO `redistributed distribution N (…, class N)` 기준: 42 · 43 → 학급 1, 8 · 44 → 학급 2, 5 · 6 · 7 → 학급 3. 41 의 학급은 로그에 없어 별도 표기)

```bash
awk '$7~/redistribute/{t=substr($4,14,8); p=(t<"13:41:48")?"before":"after"; split($7,a,"/"); print p,$1,a[4],$9}' $L/nginx-access.log | sort | uniq -c
awk '$7~/redistribute/ && $9==200{t=substr($4,14,8); p=(t<"13:41:48")?"before":"after"; ...min/max $10...}' $L/nginx-access.log
```
```
before 교사단말A 41 500 ×6 / 42 200 ×5 / 43 200 ×13 / 44 200 ×17 / 8 200 ×8 / 1·2·3 409 ×31
before 교사단말B 5 200 ×4 / 6 200 ×9 / 7 200 ×10
after  교사단말A 42 500 ×19 / 43 500 ×20 / 44 200 ×1 / 8 200 ×1 / 2·3 409 ×4
after  교사단말B 5 200 ×2 / 7 200 ×2
200 응답 바이트: before 170~200, after 640~910
```

**E6** 예외 종류 · identifier 값 · Hikari 줄

```bash
grep -E '^(org|java|com|jakarta)\.[A-Za-z.]+Exception' $L/app.log | sed -E 's/`[0-9]+`/`N`/g' | sort | uniq -c
grep -oE 'identifier value `[0-9]+`' $L/app.log | sort | uniq -c
grep -nE 'Hikari' $L/app.log | cut -c1-140
```
```
     46 org.springframework.orm.jpa.JpaObjectRetrievalFailureException: Entity `com.example.assignment.Assignment` with identifier value `N` does not exist
     92 identifier value `9`
1243: … HikariDataSource : itembank-pool - Shutdown initiated...
1244: … HikariDataSource : itembank-pool - Shutdown completed.
1258: … HikariDataSource : itembank-pool - Starting...
1259: … HikariPool       : itembank-pool - Added connection …
1260: … HikariDataSource : itembank-pool - Start completed.
```

**E7** 예외 호출 지점 · 재배포 INFO 직후 ERROR 짝

```bash
grep -oE 'at com\.example\.assignment\.Distribution(Service|Controller)\.[a-zA-Z]+\([A-Za-z.]+:[0-9]+\)' $L/app.log | sort | uniq -c
awk 'NR>1264{ if(/ ERROR / && prev ~ /redistributed distribution/) c++; if(/^2026/) prev=$0 } END{print c}' $L/app.log
```
```
      1 at com.example.assignment.DistributionController.getDistribution(DistributionController.java:26)
      6 at com.example.assignment.DistributionController.redistribute(DistributionController.java:38)
     39 at com.example.assignment.DistributionController.redistribute(DistributionController.java:40)
      1 at com.example.assignment.DistributionService.getDistribution(DistributionService.java:32)
     39 at com.example.assignment.DistributionService.listByClass(DistributionService.java:38)
      7 at com.example.assignment.DistributionService.loadOrThrow(DistributionService.java:65)
      6 at com.example.assignment.DistributionService.redistribute(DistributionService.java:51)
39
```

**E8** 재기동 후 재배포 INFO 의 학급 분포 · 학급 2 · 3 재배포 직후 줄

```bash
awk 'NR>1264 && /redistributed distribution/' $L/app.log | grep -oE 'class [0-9]+' | sort | uniq -c
awk 'NR>1264 && /redistributed distribution/ && /class [23]\)/{print NR}' $L/app.log | while read n; do sed -n "$((n+1))p" $L/app.log | cut -c1-40; done
grep -oE 'built class report for class [0-9]+: [0-9]+ distributions' $L/app.log | sort | uniq -c
```
```
     39 class 1
      2 class 2
      4 class 3
2026-09-17T13:42:33.771+09:00  INFO
2026-09-17T13:50:01.497+09:00  INFO
2026-09-17T13:52:13.968+09:00  INFO
2026-09-17T13:57:06.157+09:00  INFO
2026-09-17T14:21:02.317+09:00  INFO
2026-09-17T14:22:49.996+09:00  INFO
     17 built class report for class 1: 3 distributions
     24 built class report for class 2: 3 distributions
     19 built class report for class 3: 3 distributions
```
(학급 1 리포트 생성 로그는 09:01:32 `app.log:6` ~ 13:24:45 `app.log:1191` 에만 있고 재기동 후에는 없다.)

**E9** 배포 이력 줄 번호

```bash
grep -n '13:40:05\|listByClass\|단일 호스트\|history\[\]' $L/deploy-history.md
```
```
7:| v1.4.2 | 2026-09-17 13:40:05 | 2026-09-17 13:42:31 | <이메일> | 재배포 응답에 학급 배포 이력(history) 포함 — …
15:- 변경: `POST /api/distributions/{id}/redistribute` 응답 형식 변경 `{distribution}` → `{distribution, history[]}`.
16:- 변경 지점: `DistributionController.redistribute` 에서 재배포 후 `DistributionService.listByClass(classId)` 호출 추가.
18:- 배포 방식: 단일 호스트 재기동(무중단 아님). 예상 중단 10초 안팎.
```

---

마스킹 적용: IP 35건 · 이메일 1건 · 학생 식별자 4건 · 토큰 · 세션 ID · API 키 0건 · 비밀번호 · 접속 문자열 1건
