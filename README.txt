배달 자동화 통합 앱
- 내비 자동전환 ON/OFF
- 티맵 / 카카오내비 / 카카오맵 / 네이버지도 선택
- YouTube Music 자동제어 ON/OFF
- 실제 음악 재생 중일 때만 배달앱 진입 시 정지
- 이 앱이 정지시킨 음악만 배달앱을 벗어날 때 재생
- 사용자가 직접 정지한 상태는 건드리지 않음
- 재부팅 후 개별 설정 상태 복원
- 최근 앱 목록 제외
- 최초 실행 권한 순차 안내
주의: 처음 내비 URL 연결 시 Android의 앱 선택 화면에서 '배달 자동화'를 선택해야 합니다.


[출시 준비 추가]
- 런처 아이콘 적용: 일반/원형/Adaptive Icon
- Google Play 등록용 512x512 아이콘: store_assets/play_store_icon_512.png
- versionCode=1, versionName=1.0 기본 설정 유지
- 실제 Play Store 출시 전에 applicationId(com.example.musicdeliveryswitch)는 고유 패키지명으로 확정 권장
- Play Store 등록 시 접근성 서비스 및 알림 접근 권한 사용 목적에 대한 정책 검토/고지가 필요함

[2026-08-28 알림 로그 기능 추가]
- 배민(com.woowahan.bros), 쿠팡이츠 배달파트너(com.coupang.mobile.eats.courier)의 수신 알림을 분류 없이 로그로 저장합니다.
- 저장 항목: 제목/본문/bigText/subText/infoText/summary/textLines/channel/category/group/tag/id 등
- Android 10 이상 저장 위치: Download/DeliveryAutomation/배달자동화_알림로그.txt
- 실제 배달콜/이벤트/광고 판별 로직은 아직 추가하지 않았습니다. 로그 수집 후 분석하여 조건을 추가하는 용도입니다.

[2026-08-29 추가]
- 배민 신규배달: channelId=BROS_DELIVERY_ALLOCATION_NOTI + 제목=신규배달 + 본문 조건이면 배민 알림 contentIntent 자동 실행
- 쿠팡 신규주문: channelId=COURIER_ASSIGNMENT + 본문=주문을 수락해주세요 조건이면 쿠팡 알림 contentIntent 자동 실행
- 동일 패키지 2초 이내 중복 자동열기 방지
- NavigationRedirectActivity가 수신한 원본 Intent/URI, 좌표 파싱 결과, 선택 내비 실행 결과를 기존 알림 로그 파일에 함께 기록
- 로그: Download/DeliveryAutomation/배달자동화_알림로그.txt


[2026-08-29 쿠팡 네비 수정]
- 쿠팡 배달파트너 APK에서 확인된 kakaonavi-sdk://navigate 딥링크 수신 추가
- TMAP goalx/goaly 및 Kakao SDK x/y 등 목적지 좌표 파싱 범위 확대
- 쿠팡에서 TMAP/카카오내비를 SDK/명시적 Intent로 직접 실행해 RedirectActivity를 우회할 경우 접근성 서비스가 '네비화면전환' 로그 기록
- 알림 신규주문 자동열기 기능 유지
- 주의: Android 명시적 Intent(setPackage/component)는 다른 앱의 intent-filter로 가로챌 수 없음. 이 경우 네비화면전환 로그로 우회 여부를 판별함.


[신규 주문 화면전환 ON/OFF]
메인 화면의 "신규 주문 알림 시 앱 화면전환" 스위치로 제어합니다.
OFF여도 배민/쿠팡 알림 로그와 네비 로그는 계속 기록되며, 신규 주문 알림에 따른 배달앱 자동 실행만 중지됩니다. 기본값은 ON입니다.

[2026-08-31 음악 자동제어 보완]
- 배민/쿠팡에서 티맵 등 내비 앱으로 전환할 때 배달 세션을 종료하지 않도록 변경.
- 배달앱 -> 내비 사이에 런처/시스템 창이 잠깐 나타나는 경우 1.8초 지연 확인 후 재생하도록 변경.
- 결과: 티맵 플로팅 아이콘/앱 위 표시를 눌러 전환할 때 유튜브 뮤직이 약 1초 재생됐다 다시 멈추는 현상 방지.
- 배달앱과 내비 앱을 모두 벗어났을 때만 자동으로 유튜브 뮤직을 재생.

[2026-08-31 내비 플로팅 전환 음악 수정]
- 상황: 내비 앱에서 배민/쿠팡 실행 -> 내비 앱이 앱 위에 표시(플로팅) -> 플로팅 내비를 눌러 전체화면 내비로 전환 시 유튜브 뮤직이 잠깐 재생됐다가 다시 멈추는 현상.
- 수정: 티맵뿐 아니라 카카오내비/카카오맵/네이버지도 등 등록된 내비 앱 전체를 배달 세션의 연장으로 처리.
- System UI/One UI Launcher/Edge 등 중간 전환 이벤트에서는 음악 재생을 예약하지 않음.
- 내비에서 배달앱으로 진입한 경우 내비 세션 상태를 기억하고, 플로팅 내비 -> 전체화면 내비 전환 동안 음악 정지 상태를 계속 유지.
- 실제 다른 일반 앱으로 완전히 이동했을 때만 1.5초 확인 후 자동 재생.
