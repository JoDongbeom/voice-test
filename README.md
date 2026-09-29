# 사진 → AI → 음성 테스트 페이지

휴대폰 카메라로 찍은 사진을 OpenAI가 분석하고, 답변을 음성으로 읽어 블루투스 이어폰으로 듣는 최소 테스트 페이지입니다.

- 파일: `index.html` 하나 (외부 라이브러리 없음)
- API 키는 코드에 없습니다. 폰에서 처음 열 때 직접 입력하면 **그 폰의 localStorage에만** 저장됩니다.
- 모델 변경: `index.html` 상단 `const MODEL = "gpt-5.4-mini";` 한 줄만 수정 (더 저렴하게 쓰려면 `"gpt-6-luna"`)

## 휴대폰만으로 GitHub Pages에 올리기

> 카메라는 **HTTPS**에서만 제대로 동작합니다. GitHub Pages는 자동으로 HTTPS입니다.

1. 폰 크롬에서 github.com 접속 → 로그인 (앱이 아니라 웹. 메뉴 ⋮ → "데스크톱 사이트" 체크하면 편함)
2. 우측 상단 **+** → **New repository**
   - 이름: 예) `voice-test`
   - **Public** 선택 (무료 계정 Pages는 Public 필요)
   - Create repository
3. 저장소 화면에서 **Add file → Upload files** → 폰에 받아둔 `index.html` 선택 → **Commit changes**
   - 파일을 폰으로 옮기기 어렵다면: **Add file → Create new file** → 파일명 `index.html` 입력 → 내용 전체 붙여넣기 → Commit
4. 저장소 **Settings → Pages**
   - Source: **Deploy from a branch**
   - Branch: **main** / **/(root)** → Save
5. 1~2분 뒤 같은 화면 위쪽에 주소가 뜹니다: `https://<아이디>.github.io/voice-test/`
6. 그 주소를 폰 크롬에서 열기 (홈 화면에 추가해두면 편함)

## 키 관련 주의

- 키는 저장소에 올리지 마세요. 페이지 맨 아래 입력칸에만 넣습니다.
- 페이지가 Public이어도 키는 각자 폰의 브라우저 저장소에만 있으므로 다른 사람에게 노출되지 않습니다.
- 다만 브라우저에서 직접 API를 호출하는 구조이므로 **개인 테스트용**으로만 쓰고, OpenAI 대시보드에서 사용 한도(Usage limit)를 낮게 설정해두는 것을 권장합니다.
- 테스트가 끝나면 **[키 지우기]** 를 누르세요.

## 문제 해결

| 증상 | 해결 |
|---|---|
| 소리가 안 남 | 폰 미디어 볼륨 확인, [음성 테스트]부터 다시 |
| "한국어 음성이 없습니다" | 설정 → 일반/접근성 → 텍스트 음성 변환(TTS) → Google 음성 엔진 → 한국어 음성 데이터 설치 |
| 401 오류 | 키 오타. [키 지우기] 후 다시 입력 |
| 429 오류 | OpenAI 결제 잔액/한도 확인 |
| 모델 오류(400/404) | `MODEL` 값을 계정에서 쓸 수 있는 모델로 변경 |
