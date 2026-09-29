# 안드로이드 앱 설계: 화면 꺼져도 읽는 시험 답안 음성 앱

작성일: 2026-09-29

## 1. 목적

사진으로 찍은 시험 문제의 AI 모범답안을 **폰 화면이 꺼져도 끝까지** 블루투스 이어폰으로 듣는다.
브라우저 음성(speechSynthesis)은 안드로이드 크롬에서 화면이 꺼지면 멈추므로 안드로이드 앱으로 만든다.

**성공 기준**: 사진을 찍고 폰을 잠가 주머니에 넣어도 답안이 끝까지 나오고,
잠금화면 알림과 이어폰 버튼으로 이전 줄 / 재생·일시정지 / 다음 줄 조작이 된다.

**범위**
- 사용자 1명, 안드로이드 폰 1대, 플레이스토어 배포 없음
- 안드로이드 8.0(API 26) 이상
- 현재 웹 기능은 모두 유지: 촬영, AI 모범답안(지시문/분량/그래프 규칙), 기호 읽기, 줄/목차 이동, 속도 0.1~1.5, 키 저장/삭제

**범위 밖**: iOS, 스토어 배포, 답안 기록 보관, 이어폰 버튼으로 목차 이동

## 2. 구조 (웹 화면 재사용 + 음성만 네이티브)

| 부분 | 파일 | 책임 |
|---|---|---|
| 화면 | `index.html` (저장소 루트, 웹과 공용) | UI, 촬영 입력, 이미지 축소, OpenAI 호출, 기호→읽는 말 변환, 줄 목록 생성 |
| 앱 껍데기 | `MainActivity.kt` | WebView로 index.html 표시, 카메라 촬영 연결, JS 브리지 등록, 권한 요청 |
| 음성 담당 | `SpeechService.kt` | 포그라운드 서비스. Android TextToSpeech로 줄 목록 재생, 줄/목차 이동, 일시정지, 속도 |
| 잠금화면 조작 | `SpeechService.kt` 안의 MediaSession + 알림 | 알림 버튼(◀ ⏯ ▶), 이어폰 미디어 버튼 → 서비스 명령 |

### 화면이 꺼지면 WebView의 JS가 멈추므로
**읽는 위치와 다음 줄로 넘어가는 일은 전부 SpeechService가 담당한다.**
JS는 줄 목록을 한 번 넘기고, 이후에는 명령만 보내고 상태를 받는다.

## 3. JS ↔ 네이티브 인터페이스

index.html은 `window.AndroidSpeech` 존재 여부로 앱/브라우저를 판단한다.
- 있으면: 아래 브리지 사용
- 없으면: 지금처럼 speechSynthesis 플레이어 사용 (웹 버전 그대로 동작)

**JS → 네이티브** (`window.AndroidSpeech`)
목차 이동 계산(이전/다음 목차, 처음부터)은 이미 검증된 JS 로직을 그대로 쓰고, 네이티브에는 "몇 번 줄부터 읽어라"만 보낸다.
| 메서드 | 설명 |
|---|---|
| `load(json, rate)` | 줄 목록 `[{ "chunks": ["..."], "section": true/false }]`과 현재 속도를 넘김 (재생은 안 함) |
| `playFrom(index)` | index번 줄 처음부터 재생 |
| `pause()` | 일시정지 (서비스가 떠 있을 때만) |
| `setRate(r)` | 속도 0.1~1.5, 다음 조각부터 적용 |

잠금화면/이어폰의 이전 줄·재생/일시정지·다음 줄은 네이티브(PlayerState)가 직접 처리한다.

**네이티브 → JS**
- `window.onNativeSpeech({ state, pos, total, message })` — state: `playing` / `paused` / `ended`, message는 "마지막 줄입니다" 같은 안내(선택)
- `window.onNativeNotice(text)` — 한국어 음성 없음, 알림 권한 거부, 배터리 안내(최초 1회)를 화면 경고 칸에 표시
- 화면이 꺼져 있는 동안의 호출은 버려져도 된다. 다시 보일 때(onResume) 서비스가 현재 상태를 한 번 보낸다.

테스트 음성, 오류 안내 음성(짧은 1회성)도 같은 `load`로 보내되 JS가 상태 표시를 덮어쓰지 않도록 기존 quiet 처리를 유지한다.

## 4. 네이티브 세부

- **WebView 로딩**: `WebViewAssetLoader`로 `https://appassets.androidplatform.net/assets/index.html` 형태로 연다.
  https origin이 되어 localStorage와 OpenAI fetch(CORS)가 웹과 같이 동작한다. `domStorageEnabled = true`.
- **촬영**: `WebChromeClient.onShowFileChooser`에서 `MediaStore.ACTION_IMAGE_CAPTURE` + `FileProvider` URI로 후면 카메라 실행, 결과 URI를 file input에 돌려준다. CAMERA 권한 선언 없이 외부 카메라 앱 사용.
- **TTS**: `TextToSpeech`, `Locale.KOREAN`. 조각마다 `speak(QUEUE_FLUSH/ADD)`와 `UtteranceProgressListener.onDone`으로 다음 조각 진행. 이동/일시정지는 `stop()` 후 해당 줄 처음부터.
- **포그라운드 서비스**: `foregroundServiceType="mediaPlayback"`, 권한 `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`(13+ 런타임 요청), `WAKE_LOCK`.
  서비스가 살아 있는 동안은 일시정지 중에도 계속 포그라운드로 둔다(안드로이드 12+의 백그라운드 재시작 제한을 피하려고). 알림에 ✕(닫기) 버튼을 두어 누르면 서비스 종료. 부분 WakeLock은 재생 중에만 잡는다.
- **MediaSession**: `MediaSessionCompat` 콜백 onPlay/onPause/onSkipToNext(다음 줄)/onSkipToPrevious(이전 줄). 알림은 `MediaStyle`로 ◀ ⏯ ▶.
  TTS 소리는 TTS 엔진 앱이 내는 소리라 안드로이드가 우리 앱을 "재생 중인 앱"으로 보지 않을 수 있다. 그래서 읽는 동안 **무음 오디오를 우리 앱에서 재생**하고 오디오 포커스를 요청해 이어폰 버튼이 우리 앱으로 오게 한다.
  전화/다른 앱 재생으로 포커스를 잃거나 이어폰 연결이 끊기면(AUDIO_BECOMING_NOISY) 일시정지한다.
- **키/속도 저장**: WebView localStorage 그대로 (앱 전용 저장소, 웹과 별개).

## 5. 빌드·배포

- 저장소 구조: 루트 `index.html`, `android/` (Gradle 프로젝트, Kotlin), `.github/workflows/android.yml`
- 빌드 시 루트 `index.html`을 `android/app/src/main/assets/index.html`로 복사 (Gradle 태스크 또는 워크플로 단계). assets 사본은 커밋하지 않음.
- GitHub Actions: main에 push되면 JDK 17 + Gradle로 `assembleRelease`, 서명, Releases에 `voice-test.apk` 업로드(태그 자동 증가, latest 갱신).
- 설치 링크 고정: `https://github.com/JoDongbeom/voice-test/releases/latest/download/voice-test.apk`
- **서명**: PKCS12 키스토어를 한 번 생성해 base64로 GitHub Secrets(`KEYSTORE_B64`, `KEYSTORE_PASSWORD`)에 저장. 저장소에는 절대 커밋하지 않음. 서명이 같아 덮어쓰기 업데이트 가능.
- `versionCode`는 워크플로 실행 번호(`GITHUB_RUN_NUMBER`)로 자동 증가.

## 6. 오류 처리

| 상황 | 대응 |
|---|---|
| 한국어 TTS 없음 | 화면에 "설정 > 텍스트 음성 변환에서 한국어 음성 설치" 안내 |
| 알림 권한 거부 | 읽기는 동작, "화면이 꺼지면 멈출 수 있음" 경고 표시 |
| 제조사 배터리 절약으로 종료 | 화면에 배터리 "제한 없음" 설정 안내 (최초 1회) |
| 카메라 취소 | 아무 일 없음 (대기) |
| API/네트워크 오류 | 기존과 동일 (화면 + 음성) |
| TTS 초기화 실패 | 화면에 오류 표시 |

## 7. 테스트

- **자동/PC**: GitHub Actions 빌드 성공. index.html은 PC 브라우저에서 가짜 `window.AndroidSpeech`를 주입해 명령 호출 순서, 상태 콜백 반영, 브라우저 폴백 동작 확인.
- **폰 수동 체크리스트** (실기기에서만 가능)
  1. 설치, 알림 권한 허용, 키 입력
  2. 음성 테스트가 이어폰으로 들림
  3. 사진 → 답안 → 읽기 시작
  4. 화면 끄고 끝까지 읽음
  5. 잠금화면 ◀ ⏯ ▶ 동작
  6. 이어폰 버튼 재생/일시정지, 다음/이전 동작
  7. 화면 다시 켜면 줄 위치 표시가 맞음
  8. 새 버전 덮어쓰기 설치 후 키/속도 유지
