# 📦 parkwoong-binance — Casual README & Architecture Walkthrough

> **Heads up**: This is a personal research/learning project that connects to **Binance Futures** market data and pings **Telegram** when trade triggers appear. It does **not** place orders. None of this is financial advice. 😄

---

## 🚀 What this project does (in one breath)

* Pulls **ETHUSDT** candlesticks from Binance (**REST**) and listens to live candles via **WebSocket** (`15m` + `1h`).
* Computes indicators (EMA, RSI, Bollinger Bands, ADX/DMI) using **TA4J**.
* Derives a daily **Bias** (LONG/SHORT/NEUTRAL) and a 4‑hour **Regime** (TREND/RANGE).
* Maintains live buffers per timeframe and, on every **closed** candle, checks for **entry triggers**.
* When conditions line up, sends a neat **Telegram** message with entry/stop/take-profit context.

---

## 🧭 Quick start

### Requirements

* **Java 17**
* **Maven Wrapper** included (`./mvnw`) — no global Maven needed.

### 1) Clone & build

```bash
./mvnw clean package -DskipTests
```

### 2) Configure your `application.yml`

```yaml
spring:
  application:
    name: binance
  profiles:
    active: local

binance:
  domain:
    main: "https://fapi.binance.com"
    get:
      time: "/fapi/v1/time"
    klines: "/fapi/v1/klines"
    ws: "wss://fstream.binance.com/stream"

telegram:
  domain: https://api.telegram.org/bot${telegram.token}/sendMessage
  token: <PUT_YOUR_TELEGRAM_BOT_TOKEN>
  chatId: <PUT_YOUR_CHAT_ID>
```

> `telegram.token` and `telegram.chatId` must be available at runtime (via env vars, profile file, etc.).

### 3) Run it

```bash
./mvnw spring-boot:run
```

You’ll see logs about bootstrap, WS connection, and refresh jobs.

---

## 🧪 Handy endpoints (for local testing)

**Controller:** `BinanceScanController`

* Start (or reuse) a live session and evaluate bias/regime instantly:

  ```http
  GET /trade/test?coin=ETHUSDT
  -> "bias=LONG, regime=TREND"
  ```
* Inspect current sessions:

  ```http
  GET /session/state
  ```
* Close a live session (WS):

  ```http
  GET /session/close?symbol=ETHUSDT
  ```
* Manually send a Telegram message:

  ```http
  GET /telegram?message=hello
  ```

> Default symbol in schedules is **ETHUSDT** (see `ScheduledConfig`). You can change it.

---

## 🧱 Project layout (tl;dr)

```
src/main/java/com/example/binance/
├─ BinanceApplication.java              # Spring Boot entry
├─ config/
│  ├─ ScheduledConfig.java             # Startup + cron for refresh
│  └─ WebClientConfig.java             # Reactive WebClient + helpers
├─ controller/
│  └─ BinanceScanController.java       # Test endpoints
├─ dto/
│  ├─ BiasRegime.java                  # Bias(LONG/SHORT/NEUTRAL), Regime(TREND/RANGE)
│  ├─ BootStrapEnv.java                # H1 environment gate for triggers
│  └─ Candle.java                      # OHLCV + open/close time
├─ enums/TimeFrame.java                 # D1/H4/H1/M15
├─ properties/
│  ├─ DomainProperties.java            # binance domains + paths (incl. nested get.time)
│  └─ TelegramProperties.java          # telegram domain/token/chatId
├─ service/
│  ├─ BInanceRestService.java          # (typo in name) REST klines fetcher
│  ├─ IndicatorService.java            # TA4J adapters: EMA/RSI/BB/ADX
│  ├─ DirectionService.java            # Computes daily bias + 4h regime
│  ├─ KlineSocketService.java          # WS client; buffers; bootstraps; trigger loop
│  ├─ CalculateService.java            # H1-gated M15 trigger logic + notifier
│  └─ TriggerService.java              # Alt trigger logic (time-windowed)
├─ utils/
│  ├─ DateParser.java
│  └─ Notifier.java                    # Telegram caller
└─ ws/SymbolSession.java               # Per-symbol state (buffers, refs, ws)
```

---

## 🔄 End‑to‑end flow (how it actually works)

### 0) Startup

* `ScheduledConfig#checkProperties()` triggers on **ApplicationReady**:

  1. `DirectionService#evaluate("ETHUSDT")` computes **Bias** (via D1 EMA21/EMA50 + RSI) and **Regime** (via H4 ADX≥22 ⇒ TREND).
  2. `KlineSocketService#start()` creates a **SymbolSession**:

     * Bootstrap **H1** and **M15** buffers with REST klines (up to 200 candles).
     * Compute **H1 BootStrapEnv** (gates like `longOk`/`shortOk`, BB mid/up/low, `hourKey`).
     * Open **WS** stream for `kline_15m` and `kline_1h`.

### 1) Live loop (every closed candle)

* WS message arrives → parse JSON `k` payload → only process when `x == true` (closed candle).
* Append candle into the right buffer (H1/M15). Deduplicate by `closeTime`.
* If **H1** closed → recompute `BootStrapEnv` (EMA/RSI/BB on latest 1h slice) and store.
* If **M15** closed → run trigger evaluation (see below).

### 2) Trigger checks (M15, gated by H1)

* `CalculateService#onCandleClosed_H1M15()` does roughly:

  1. Ensure buffers are warm (`H1 ≥ 60`, `M15 ≥ 60`).
  2. Ensure current M15 belongs to the latest H1 window (`hourKey` match).
  3. Calculate indicators: `BB(20,2)` and `RSI(14)` on M15; `BB(20,2)` on H1.
  4. Build **momentum** flags: volume spike vs 20‑avg, RSI recovery across 50, etc.
  5. If `H1.longOk` then allow a **LONG** trigger when:

     * M15 mid‑band **recapture** or **upper‑band break**, **and** momentum OK.
  6. Else if `H1.shortOk` then allow a **SHORT** trigger when:

     * M15 mid‑band **loss** or **lower‑band break**, **and** momentum OK.
  7. On trigger → compose a neat text with **entry / stop / TP1(M15 BB) / TP2(H1 BB)** and send via Telegram. Duplicate suppression via a small key (symbol|tf|side|closetime).

> There’s an alternative time‑window trigger (`TriggerService`) that only runs between **14:00–22:00 AEST**. You can pick one style.

---

## 🧠 Indicators & rules (short version)

* **Bias (D1)**: `EMA21 vs EMA50` + `RSI >=/<= 50` → LONG/SHORT/NEUTRAL.
* **Regime (H4)**: `ADX(14) >= 22 ⇒ TREND`, else RANGE.
* **H1 Gate (BootStrapEnv)**: relaxed checks (EMA21 vs EMA50, RSI 48/52 band, price vs BB mid) → `longOk` / `shortOk`.
* **M15 Trigger**: mid‑band cross / band break + momentum (vol spike or RSI recovery or high RSI/low RSI).

---

## 🗓️ Scheduling (AEST)

`ScheduledConfig` uses **Australia/Sydney** timezone:

* Every **4 hours on the hour**: re‑evaluate Bias/Regime and update the running session.
* Every **day at 00:05**: same refresh (extra safety).

You can tweak the cron expressions if your trading window changes.

---

## ⚙️ Configuration notes

* `DomainProperties` binds to `binance.domain.*` and has a nested `get.time`. Your YAML already follows that structure.
* `WebClientConfig` provides static helpers `getSend`/`postSend`. They reuse one shared `WebClient` (reactor‑netty with timeouts).
* **Telegram**: `Notifier` posts a JSON body to `telegram.domain` (which expands to `/bot<token>/sendMessage`).

---

## 🔍 Known nits & small TODOs

* **Typo**: `BInanceRestService` → should be `BinanceRestService` (class + bean name).
* **Compute env bug**: In `KlineSocketService#computeH1Env`, the builder sets `bbUp` twice; the second one should be `bbLow`:

  ```java
  return BootStrapEnv.builder()
      .hourKey(hourKey)
      .longOk(longOk)
      .shortOk(shortOk)
      .bbMid(bb[0])
      .bbUp(bb[1])
      .bbLow(bb[2]) // ← fix here
      .build();
  ```
* **Symbol flexibility**: default coin is `ETHUSDT` (hard‑coded in `ScheduledConfig`). Consider making it configurable via `application.yml` or env.
* **Backtest**: Live triggers are cool, but add a backtesting module for sanity checks.
* **Resilience**: On WS failure it restarts, but you may want exponential backoff and metrics.
* **Unit tests**: Add tests for indicator adapters and trigger edges (mid‑band epsilon, RSI crossover, etc.).

---

## 🧰 Local curl cheatsheet

```bash
# Start a live session and get current bias/regime
curl "http://localhost:8080/trade/test?coin=ETHUSDT"

# Session state
curl http://localhost:8080/session/state

# Close WS session
curl "http://localhost:8080/session/close?symbol=ETHUSDT"

# Send a manual Telegram message
curl "http://localhost:8080/telegram?message=Ping"
```

---

## 🧪 How to tweak the strategy (safe knobs)

* **Momentum sensitivity**: In `CalculateService`, adjust `1.2 * avgVol`, and RSI thresholds (`49→50`, `55`, `45`).
* **Bollinger epsilon**: `eps = 0.0005 * last.close` is the mid‑band tolerance. Narrow/widen to control noise.
* **Regime strictness**: Change `ADX >= 22` if you want more/less TREND classification.
* **H1 gate**: The `longScore/shortScore >= 2` rule is a chill gate—tighten or loosen to change signal frequency.

---

## 🛟 Troubleshooting

* **No WS messages**: Double‑check `binance.domain.ws` is `wss://fstream.binance.com/stream` and your symbol is valid.
* **NPE on buffers**: Bootstraps fetch 200 candles; ensure network access. Logs show sizes on first run.
* **Telegram not sending**: Verify `token` and `chatId`. The app logs errors from `Notifier`.
* **Different timezones**: All indicators are built from raw exchange timestamps; `AEST` only applies to schedules and the alt trigger window.

---

## 📜 License

Apache License 2.0 (wrapper scripts carry ASF headers). For your own code, add a LICENSE if you plan to share.

---

## 🧠 Appendix — Mental model (Mermaid)

```
flowchart TD
    A[App Ready] --> B[DirectionService D1/H4]
    B -->|Bias/Regime| C[Start Session]
    C --> D[Bootstrap H1/M15 via REST]
    D --> E[Open WebSocket 15m & 1h]
    E -->|closed 1h| F[Recompute H1 Env]
    E -->|closed 15m| G[Trigger Eval (M15 gated by H1)]
    G -->|trigger| H[Telegram Notifier]
```

If you want, I can split this into a **README.md** and a separate **docs/PROCESS.md**, or tailor the README for GitHub with shields, prettier sections, and a demo GIF later. 👍
