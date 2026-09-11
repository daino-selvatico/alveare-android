# Alveare Android Voice Satellite — Verification & Audit Report

## 1. Initial Codebase Audit & Architectural Gaps

Prior to this implementation lane, the native Android app had several critical architectural deficiencies:
1. **Misleading Wake Word Recognition**: The legacy `WakeWordDetector` was merely an amplitude/energy-burst spike counter (`processAudioSample(amp >= sensitivity)`) advertised as vocal keyword recognition. It constantly produced false positives from background door slams, music, claps, or coughs.
2. **Double AudioRecord Ownership Risk**: If wake phrase and streaming audio were both running, they risked competing for the single hardware microphone, causing device errors or audio loss.
3. **Hardcoded 44-byte WAV Assumption**: The audio player discarded bytes 0–43 assuming a fixed 44-byte header. Standard WAV headers containing metadata, `JUNK`, or variable `fmt ` chunks led to audio corruption or loud click artifacts.
4. **Sample Rate Misalignment**: Handshake returned `sample_rate=16000` (server input rate), whereas Kokoro 82M synthesized output is 24000 Hz WAV.
5. **No Persistent Foreground Ownership**: Persistent audio capture ran inside `MainActivity`, vulnerable to background death or screen-off termination without an ongoing foreground notification.
6. **UI Churn & Autoscroll Jitter**: Chat messages were unconditionally scrolled on every streaming token (`onAssistantDelta`), disrupting the user when reading history.

---

## 2. Vertical TDD Implementation Trajectory

We implemented each component using vertical Test-Driven Development (failing test -> implementation -> passing test).

### TDD Step 1: `WavParser` (RIFF Chunk Extraction)
- **Failing test execution**:
  ```
  > Task :app:compileDebugUnitTestKotlin FAILED
  e: WavParserTest.kt: Unresolved reference: WavParser
  BUILD FAILED in 5s
  ```
- **Implementation**: Created `app/src/main/java/com/alveare/satellite/audio/WavParser.kt` parsing RIFF containers, reading `fmt ` chunk properties and finding variable-offset `data` chunks.
- **Passing test execution**:
  ```
  > Task :app:testDebugUnitTest
  BUILD SUCCESSFUL in 2s
  ```

### TDD Step 2: `AppPreferences` & `ReconnectBackoff`
- **Failing test execution**:
  ```
  > Task :app:compileDebugUnitTestKotlin FAILED
  e: AppPreferencesAndNetworkTest.kt: Unresolved reference: ReconnectBackoff
  BUILD FAILED in 1s
  ```
- **Implementation**: Created `ReconnectBackoff.kt` (bounded exponential backoff 1s–30s) and updated `normalizeServerUrl` to preserve user-specified `ws://` schemes without silent TLS escalation.
- **Passing test execution**:
  ```
  > Task :app:testDebugUnitTest
  BUILD SUCCESSFUL in 2s
  ```

### TDD Step 3: `CircularAudioBuffer` (Pre-Roll Protection)
- **Failing test execution**:
  ```
  > Task :app:compileDebugUnitTestKotlin FAILED
  e: CircularAudioBufferTest.kt: Unresolved reference: CircularAudioBuffer
  BUILD FAILED in 855ms
  ```
- **Implementation**: Created thread-safe `CircularAudioBuffer.kt` retaining the last ~800ms of PCM to ensure user speech right after or during the wake phrase is not trimmed.
- **Passing test execution**:
  ```
  > Task :app:testDebugUnitTest
  BUILD SUCCESSFUL in 1s
  ```

### TDD Step 4: `VoskWakeWordDetector` (Offline Lexical Recognition)
- **Failing test execution**:
  ```
  > Task :app:compileDebugUnitTestKotlin FAILED
  e: VoskWakeWordDetectorTest.kt: Unresolved reference: VoskWakeWordDetector
  BUILD FAILED in 803ms
  ```
- **Implementation**: Created `VoskWakeWordDetector.kt` using official Vosk/Kaldi grammar restriction `["ehi alveare", "[unk]"]`, debouncing, and self-trigger prevention while assistant speaks.
- **Passing test execution**:
  ```
  > Task :app:testDebugUnitTest
  BUILD SUCCESSFUL in 1s
  ```

### TDD Step 5: `VoskModelManager` (Model Lifecycle & Storage)
- **Failing test execution**:
  ```
  > Task :app:compileDebugUnitTestKotlin FAILED
  e: VoskModelManagerTest.kt: Unresolved reference: VoskModelManager
  BUILD FAILED in 764ms
  ```
- **Implementation**: Created `VoskModelManager.kt` validating model directory structures, streaming official downloads (~43MB) from Alpha Cephei, and extracting safely.
- **Passing test execution**:
  ```
  > Task :app:testDebugUnitTest
  BUILD SUCCESSFUL in 2s
  ```

### TDD Step 6: `SatelliteStateMachine` (Assistant vs Live Modes)
- **Failing test execution**:
  ```
  > Task :app:compileDebugUnitTestKotlin FAILED
  e: SatelliteStateMachineTest.kt: Unresolved reference: SatelliteStateMachine
  BUILD FAILED in 1s
  ```
- **Implementation**: Created `SatelliteStateMachine.kt` governing transitions between Assistant mode (one utterance -> answer -> waiting) and Live mode (continuous full duplex).
- **Passing test execution**:
  ```
  > Task :app:testDebugUnitTest
  BUILD SUCCESSFUL in 1s
  ```

---

## 3. Real Gradle Verification Commands & Outputs

### Command: `./gradlew testDebugUnitTest`
```
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 2s
26 actionable tasks: 6 executed, 20 up-to-date
```
- **Total Tests**: 30
- **Failures**: 0
- **Ignored**: 0
- **Success Rate**: 100%

Test breakdown:
- `com.alveare.satellite.AlveareLiveWebSocketTest`: 4 passed
- `com.alveare.satellite.AppPreferencesAndNetworkTest`: 8 passed
- `com.alveare.satellite.audio.CircularAudioBufferTest`: 3 passed
- `com.alveare.satellite.audio.WavParserTest`: 4 passed
- `com.alveare.satellite.state.SatelliteStateMachineTest`: 5 passed
- `com.alveare.satellite.wakeword.VoskModelManagerTest`: 3 passed
- `com.alveare.satellite.wakeword.VoskWakeWordDetectorTest`: 3 passed

### Command: `./gradlew lintDebug`
```
> Task :app:lintReportDebug
Wrote HTML report to file:///home/daino/progetti/alveare-android/app/build/reports/lint-results-debug.html
> Task :app:lintDebug

BUILD SUCCESSFUL in 20s
30 actionable tasks: 13 executed, 17 up-to-date
```
- **Result**: 0 errors, 0 build-blocking issues.

### Command: `./gradlew assembleDebug`
```
BUILD SUCCESSFUL in 6s
45 actionable tasks: 19 executed, 26 up-to-date
```

---

## 4. Build Artifacts & Packaging Details

- **APK Exact Path**:
  `/home/daino/progetti/alveare-android/app/build/outputs/apk/debug/app-debug.apk`
- **File Size**:
  `34,037,907 bytes` (~32.46 MiB / 33 MB)
- **Version Code**: `2`
- **Version Name**: `"1.1.0"`
- **Minimum SDK**: `24` (Android 7.0 Nougat preserved)
- **Target SDK**: `34` (Android 14)
- **Native Architectures**:
  `armeabi-v7a` (32-bit old ARM phones), `arm64-v8a` (64-bit modern ARM phones), `x86_64` (emulators & PCs).

---

## 5. Real Device Testing Status & Honest Assessment

- **Host & Unit Tests**: Verified hermetically with 30 unit tests covering network protocol, WAV decoding, circular pre-roll buffer, state machine transitions, and lexical keyword detection.
- **Physical Device Deployment**: Pending real physical device deployment on old test phones. Real device tests should evaluate:
  1. Battery drain in continuous foreground microphone service over 4+ hours.
  2. Acoustic Echo Cancellation (AEC) hardware performance across different vendor chipsets (e.g. older Qualcomm vs MediaTek).
  3. Micro-lag on low-end 1GB/2GB RAM 32-bit devices when running the Vosk Kaldi graph in the background.
