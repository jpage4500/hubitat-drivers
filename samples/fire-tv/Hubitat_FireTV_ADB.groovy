    /**
    *  Hubitat - Amazon Fire TV / Firestick - ADB Driver
    * Controls Fire TV directly via ADB TCP protocol (without an intermediary server) *
    *  Copyright 2026 Vartan Horigian VH / TRATO  |  Apache 2.0
    *  1.5.2026 - Versão 1.1 - 
    *  1.5.2026 - Versão 1.2 
    - Added functions and defs to use the same commands that Samsung Remote uses (arrowUp, arrowLeft, etc)
    - Added Function "appOpenByName", to receive the string(name of App, ex: Netflix, Amazon Prime, YouTube)
    - Added CurrentApp attribute parse with poll every 30 seconds (configure in preferences)
    *  2.5.2026 - Versão 1.3 
        - Fixed Authorization hangout loop. You need to :
        On Fire TV → Settings → My Fire TV → Developer Options → Revoke ADB Authorizations
        Then load the new driver on Hubitat and click any command
        On the Fire TV screen, the dialog box "Authorize ADB?" will appear → select "Always Allow"
    *  21.5.2026 - Versão 1.4 
        - Fixed AppleTV launch command and added HBOMax launch command. 
        - Preserves TCP connection when updating preferences. 
    *
    *  INITIAL SETUP:
    * 1. Firestick → Settings → My Fire TV → Developer Options → ADB Debugging: ON
    * 2. Install the driver, configure the IP address, and save.
    * 3. Click any command — the TV will ask "Authorize ADB?"
    * 4. Select "Always allow" → done, authorized forever.

    */

    import groovy.transform.Field

    // ─── ADB Protocol Constants ───────────────────────────────────────────────────
    @Field static final int CMD_CNXN = 0x4e584e43
    @Field static final int CMD_AUTH = 0x48545541
    @Field static final int CMD_OPEN = 0x4e45504f
    @Field static final int CMD_OKAY = 0x59414b4f
    @Field static final int CMD_CLSE = 0x45534c43
    @Field static final int CMD_WRTE = 0x45545257

    @Field static final int AUTH_TOKEN        = 1
    @Field static final int AUTH_SIGNATURE    = 2
    @Field static final int AUTH_RSAPUBLICKEY = 3

    @Field static final int ADB_VERSION = 0x01000000
    @Field static final int MAX_PAYLOAD = 4096
    @Field static final int LOCAL_ID    = 1

    // ─── Android Key Event Codes ─────────────────────────────────────────────────
    @Field static final Map KEY = [
        HOME:3, BACK:4, MENU:82,
        DPAD_UP:19, DPAD_DOWN:20, DPAD_LEFT:21, DPAD_RIGHT:22, DPAD_CENTER:23,
        VOLUME_UP:24, VOLUME_DOWN:25, VOLUME_MUTE:164,
        POWER:26, WAKEUP:224, SLEEP:223,
        PLAY_PAUSE:85, STOP:86, NEXT:87, PREV:88,
        REWIND:89, FF:90, PLAY:126, PAUSE:127,
        ENTER:66, ESCAPE:111
    ]

    // ─── App Package Names ────────────────────────────────────────────────────────
    @Field static final Map APPS = [
        netflix: "com.netflix.ninja",
        prime:   "com.amazon.firebat",
        youtube: "com.amazon.firetv.youtube",
        disney:  "com.disney.disneyplus",
        hbo:     "com.hbo.hbonow",
        appletv: "com.apple.atve.amazon.appletv",
        hulu:    "com.hulu.plus",
        plex:    "com.plexapp.android",
        spotify: "com.spotify.music",
        twitch:  "tv.twitch.android.app"
    ]

    // ─── RX Buffer estático (sobrevive entre callbacks parse() na mesma sessão) ───
    @Field static final Map rxBuf = [:]

    // ─────────────────────────────────────────────────────────────────────────────

    metadata {
        definition(
            name:      "Amazon Fire TV (ADB)",
            namespace: "TRATO",
            author:    "VH"
        ) {
            capability "Switch"
            capability "Refresh"

            command "home"
            command "back"
            command "menu"
            command "wakeUp"
            command "sleepDevice"
            command "dpadUp"
            command "dpadDown"
            command "dpadLeft"
            command "dpadRight"
            command "arrowLeft"
            command "arrowRight"
            command "arrowUp"
            command "arrowDown"
            command "select"
            command "enter"
            command "numericKeyPad"
            command "volumeUp"
            command "volumeDown"
            command "mute"
            command "play"
            command "pause"
            command "playPause"
            command "stop"
            command "fastForward"
            command "fastBack"
            command "rewind"
            command "nextTrack"
            command "previousTrack"
            command "guide"
            command "sourceSetOSD"
            command "sourceToggle"
            command "channelList"
            command "channelUp"
            command "channelDown"
            command "channelSet",      [[name:"Channel*", type:"STRING",
                                        description:"Canal"]]
            command "previousChannel"
            command "exit"
            command "Return"
            command "launchNetflix"
            command "launchPrimeVideo"
            command "launchYouTube"
            command "launchDisneyPlus"
            command "launchHBOMax"
            command "launchAppleTV"
            command "appOpenByName",    [[name:"AppName*", type:"STRING",
                                        description:"Nome do app (Netflix, PrimeVideo, Disney, YouTube, AppleTV, HBOMax)"]]
            command "launchApp",        [[name:"PackageName*", type:"STRING",
                                        description:"Package (ex: com.netflix.ninja)"]]
            command "sendKeyEvent",     [[name:"KeyCode*",     type:"NUMBER",
                                        description:"Android key code"]]
            command "sendShellCommand", [[name:"Command*",     type:"STRING",
                                        description:"ADB shell command"]]
            command "getCurrentApp"
            command "generateNewKey"
            command "disconnect"
            attribute "adbStatus",  "string"
            attribute "currentApp", "string"
            attribute "currentAppName", "string"        
        }

        preferences {
            input name: "ipAddress", type: "text",   title: "Fire TV IP Address",  required: true
            input name: "adbPort",   type: "number", title: "ADB Port",       defaultValue: 5555, required: true
            input name: "logEnable", type: "bool",   title: "Debug Logging",   defaultValue: true
            input name: "poltime",   type: "number", title: "Poll Time(secs)",       defaultValue: 30, required: false
            
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════════

    def installed() {
        log.info "[FireTV] Driver installed"
        state.connState = "IDLE"
        sendEvent(name: "adbStatus",  value: "disconnected")
        sendEvent(name: "switch",     value: "off")
        sendEvent(name: "currentApp", value: "unknown")
        generateKeyPair()
    }

    def updated() {
        log.info "[FireTV] Configurações atualizadas"
        if (!state.adbPublicKey || !state.adbKeyD) generateKeyPair()
        state.waitingForUserAuth = false
        boolean ipChanged   = (state.lastIp   != settings.ipAddress)
        boolean portChanged = (state.lastPort != (settings.adbPort as String))
        if (ipChanged || portChanged) {
            log.info "[FireTV] IP/porta alterados — reconectando"
            state.lastIp   = settings.ipAddress
            state.lastPort = settings.adbPort as String
            closeSocket()
        }
        unschedule()
        runIn(30, "pollCurrentApp")
    }

    def uninstalled() {
        closeSocket()
    }

    def initialize() {
        state.connState  = "IDLE"
        rxBuf[device.id] = ""
        if (!state.adbPublicKey) generateKeyPair()
        sendEvent(name: "adbStatus", value: "disconnected")
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // HELPERS — LITTLE-ENDIAN (sem java.nio)
    // ═══════════════════════════════════════════════════════════════════════════════

    private byte[] int32LE(long val) {
        return [
            (byte)( val        & 0xFF),
            (byte)((val >>  8) & 0xFF),
            (byte)((val >> 16) & 0xFF),
            (byte)((val >> 24) & 0xFF)
        ] as byte[]
    }

    // Concatena múltiplos byte arrays (o operador + não funciona no sandbox do Hubitat)
    private byte[] concatBytes(List<byte[]> arrays) {
        int total = 0
        for (byte[] a : arrays) { if (a) total += a.length }
        byte[] result = new byte[total]
        int pos = 0
        for (byte[] a : arrays) {
            if (a) { for (int i = 0; i < a.length; i++) { result[pos++] = a[i] } }
        }
        return result
    }

    private long readInt32LE(byte[] buf, int offset) {
        return ((buf[offset]     & 0xFFL)      ) |
            ((buf[offset + 1] & 0xFFL) <<  8) |
            ((buf[offset + 2] & 0xFFL) << 16) |
            ((buf[offset + 3] & 0xFFL) << 24)
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // RSA KEY — gerado via BigInteger puro (sem java.security.KeyPairGenerator)
    // ═══════════════════════════════════════════════════════════════════════════════

    def generateKeyPair() {
        log.info "[FireTV] Generating RSA 2048 RSA Key via BigInteger..."
        try {
            def rng = new java.util.Random(now())
            BigInteger p = new BigInteger(1024, 64, rng)
            BigInteger q = new BigInteger(1024, 64, rng)
            while (q == p) { q = new BigInteger(1024, 64, rng) }

            BigInteger n   = p.multiply(q)
            BigInteger phi = p.subtract(BigInteger.ONE).multiply(q.subtract(BigInteger.ONE))
            BigInteger e   = BigInteger.valueOf(65537L)
            BigInteger d   = e.modInverse(phi)

            // Salva chave pública (N e exponent) e privada (d) em state
            state.adbKeyN      = n.toString(16)
            state.adbKeyD      = d.toString(16)
            state.adbPublicKey = buildAdbPublicKey(n, 65537)

            log.info "[FireTV] Key generated. In your 1st connection please authorize in your TV Screen."
        } catch (Exception ex) {
            log.error "[FireTV] Failed to generate the key: ${ex.message}"
        }
    }

    def generateNewKey() {
        state.adbPublicKey = null
        generateKeyPair()
        sendEvent(name: "adbStatus", value: "nova_chave_gerada")
        log.warn "[FireTV] New key generated  — please authorizae your TV again."
    }

    // Constrói o formato binário da chave pública do Android ADB (528 bytes → base64)
    private String buildAdbPublicKey(BigInteger n, int e) {
        int        w   = 64   // 2048 / 32 = 64 words
        BigInteger b32 = BigInteger.TWO.pow(32)

        // Constante de Montgomery: -n0^{-1} mod 2^32
        BigInteger n0inv = n.mod(b32).modInverse(b32).negate().mod(b32)

        // R^2 mod N com modPow eficiente (R = 2^2048)
        BigInteger rr = BigInteger.TWO.modPow(BigInteger.valueOf(4096L), n)

        // Estrutura: len(4) + n0inv(4) + n[256] + rr[256] + exponent(4) = 528 bytes
        byte[] buf = concatBytes([int32LE(w), int32LE(n0inv.longValue()),
                                bigIntToLE(n, w * 4), bigIntToLE(rr, w * 4), int32LE(e)])

        return "${buf.encodeBase64().toString().replaceAll("\\s", "")} hubitat_firetv\0"
    }

    // BigInteger (big-endian) → byte array little-endian de tamanho fixo
    private byte[] bigIntToLE(BigInteger val, int size) {
        byte[] be = val.toByteArray()
        if (be.length > size && be[0] == (byte) 0) {
            be = be[1..-1] as byte[]
        }
        byte[] result = new byte[size]
        int copy = Math.min(be.length, size)
        for (int i = 0; i < copy; i++) {
            result[i] = be[be.length - 1 - i]
        }
        return result
    }

    // BigInteger → byte array big-endian de tamanho fixo (para saída de assinatura RSA)
    private byte[] bigIntToFixedBytes(BigInteger val, int size) {
        byte[] bytes = val.toByteArray()
        if (bytes.length > size && bytes[0] == (byte) 0) {
            byte[] trimmed = new byte[bytes.length - 1]
            for (int i = 0; i < trimmed.length; i++) trimmed[i] = bytes[i + 1]
            bytes = trimmed
        }
        if (bytes.length == size) return bytes
        byte[] result = new byte[size]
        int offset = size - bytes.length
        for (int i = 0; i < bytes.length; i++) result[offset + i] = bytes[i]
        return result
    }

    // Assina o challenge token ADB com PKCS#1 v1.5 SHA-1 e a chave privada armazenada
    private byte[] signWithPrivateKey(byte[] token) {
        try {
            if (!state.adbKeyD || !state.adbKeyN) {
                log.warn "[FireTV] Private key not available"
                return null
            }
            BigInteger d = new BigInteger(state.adbKeyD as String, 16)
            BigInteger n = new BigInteger(state.adbKeyN as String, 16)

            // SHA-1 do token de desafio
            byte[] sha1 = java.security.MessageDigest.getInstance("SHA-1").digest(token)

            // PKCS#1 v1.5 DigestInfo header para SHA-1
            byte[] digestInfo = [0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
                                0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14] as byte[]

            // EM = 0x00 || 0x01 || PS (0xFF...) || 0x00 || DigestInfo || SHA1
            int keySize = 256  // 2048-bit / 8 bytes
            int psLen   = keySize - sha1.length - digestInfo.length - 3
            byte[] em   = new byte[keySize]
            em[0] = 0x00
            em[1] = 0x01
            for (int i = 0; i < psLen; i++) em[2 + i] = (byte) 0xFF
            em[2 + psLen] = 0x00
            for (int i = 0; i < digestInfo.length; i++) em[3 + psLen + i] = digestInfo[i]
            for (int i = 0; i < sha1.length; i++) em[3 + psLen + digestInfo.length + i] = sha1[i]

            // RSA: assinatura = em^d mod n
            BigInteger m   = new BigInteger(1, em)
            BigInteger sig = m.modPow(d, n)

            logD "RSA Signature Calculated (${keySize} bytes)"
            return bigIntToFixedBytes(sig, keySize)
        } catch (Exception ex) {
            log.error "[FireTV] Error to assing Token: ${ex.message}"
            return null
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // CONEXÃO TCP / SOCKET
    // ═══════════════════════════════════════════════════════════════════════════════

    private void connectToDevice() {
        if (state.connState != "IDLE") {
            logD "Already connecting (${state.connState})"
            return
        }
        if (state.waitingForUserAuth) {
            logD "Skipping reconnect — waiting for TV authorization on screen"
            return
        }
        if (!settings.ipAddress) {
            log.error "[FireTV] Without IP setup"
            return
        }
        if (!state.adbPublicKey) {
            log.warn "[FireTV] Wihout public key, generating..."
            generateKeyPair()
            if (!state.adbPublicKey) {
                log.error "[FireTV] Failed to obtain public key"
                return
            }
        }

        log.warn "[FireTV] BUILD-DEBUG-5 (features banner + named-args fix)"
        logD "Connecting to ${settings.ipAddress}:${settings.adbPort}"
        state.connState  = "CONNECTING"
        state.remoteId   = 0
        rxBuf[device.id] = ""

        try {
            interfaces.rawSocket.connect(
                settings.ipAddress,
                settings.adbPort as Integer,
                byteInterface: true,
                readDelay: 50
            )
            sendEvent(name: "adbStatus", value: "connecting")
            pauseExecution(200)
            state.connState = "AUTH_WAIT"
            sendAdbConnect()
        } catch (Exception e) {
            log.error "[FireTV] Falha TCP: ${e.message}"
            state.connState = "IDLE"
            sendEvent(name: "adbStatus", value: "erro_conexao")
        }
    }

    private void closeSocket() {
        unschedule("authPubkeyTimeout")
        unschedule("tcpKeepAlive")
        try { interfaces.rawSocket.close() } catch (e) { /* ignora */ }
        state.connState   = "IDLE"
        state.remoteId    = 0
        state.promptCount = 0
        sendEvent(name: "adbStatus", value: "disconnected")
    }

    def disconnect() {
        logD "Desconectando"
        state.waitingForUserAuth = false
        unschedule("retryAfterAuthTimeout")
        closeSocket()
    }

    def socketStatus(String message) {
        logD "Socket: ${message}"
        if (message.contains("CLOSED") || message.contains("ERROR") || message.contains("error")) {
            log.warn "[FireTV] Socket closed: ${message}"
            unschedule("forceCloseShell")
            state.connState   = "IDLE"
            state.remoteId    = 0
            state.promptCount = 0
            sendEvent(name: "adbStatus", value: "disconnected")
            // Reconecta automaticamente se havia comando pendente (e não está aguardando auth manual)
            if (state.pendingShellCmd && !state.waitingForUserAuth) {
                runIn(2, "reconnectPending")
            }
        }
    }

    def reconnectPending() {
        if (state.connState == "IDLE" && state.pendingShellCmd && !state.waitingForUserAuth) {
            logD "Reconnecting to execute pending command"
            connectToDevice()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // PROTOCOLO ADB — ENVIO
    // ═══════════════════════════════════════════════════════════════════════════════

    private void sendAdbConnect() {
        // Some ADB daemons (including some Fire TV firmware) silently drop a CNXN
        // with an empty banner. Match what the `adb` CLI sends — a features list.
        String banner = "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,abb,fixed_push_symlink_timestamp,abb_exec,remount_shell,track_app,sendrecv_v2,sendrecv_v2_brotli,sendrecv_v2_lz4,sendrecv_v2_zstd,sendrecv_v2_dry_run_send,openscreen_mdns\0"
        sendAdbMsg(CMD_CNXN, ADB_VERSION, MAX_PAYLOAD, banner.bytes)
        logD "→ CNXN (banner=${banner.length()} bytes)"
    }

    private void sendAdbMsg(int cmd, int arg0, int arg1, byte[] data) {
        int  len   = data?.length ?: 0
        long crc   = calcCRC32(data ?: new byte[0])
        long magic = (cmd ^ 0xffffffff) & 0xFFFFFFFFL

        byte[] header = concatBytes([int32LE(cmd), int32LE(arg0), int32LE(arg1),
                                    int32LE(len), int32LE(crc),  int32LE(magic)])
        byte[] msg = (len > 0) ? concatBytes([header, data]) : header

        sendRawHex(msg.encodeHex().toString().toUpperCase())
    }

    // ADB's "data_crc32" field is misleadingly named — the wire protocol expects
    // a simple sum of payload bytes (mod 2^32), not CRC32. A real CRC32 here makes
    // the device silently drop CNXN, so no AUTH challenge is sent and the
    // authorization dialog never appears on screen.
    private long calcCRC32(byte[] data) {
        if (!data || data.length == 0) return 0L
        long sum = 0L
        for (byte b : data) {
            sum = (sum + (b & 0xFFL)) & 0xFFFFFFFFL
        }
        return sum
    }

    private void sendRawHex(String hexStr) {
        log.debug "[FireTV] TX hex (${hexStr.length()} chars): ${hexStr}"
        try {
            interfaces.rawSocket.sendMessage(hexStr)
        } catch (Exception e) {
            log.error "[FireTV] Erro ao enviar: ${e.message}"
            state.connState = "IDLE"
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // PROTOCOLO ADB — RECEPÇÃO
    // ═══════════════════════════════════════════════════════════════════════════════

    def parse(String message) {
        log.debug "[FireTV] parse RAW (${message?.length() ?: 0} hex chars): ${message?.take(96)}"
        String key = device.id as String
        rxBuf[key] = (rxBuf[key] ?: "") + message.toUpperCase()

        if (rxBuf[key].length() > 131072) {
            log.warn "[FireTV] Buffer overflow, cleaning"
            rxBuf[key] = ""
            return
        }

        while (rxBuf[key].length() >= 48) {
            byte[] hdr = rxBuf[key].substring(0, 48).decodeHex()

            int cmd     = (int) readInt32LE(hdr,  0)
            int arg0    = (int) readInt32LE(hdr,  4)
            int arg1    = (int) readInt32LE(hdr,  8)
            int dataLen = (int) readInt32LE(hdr, 12)

            int totalHex = 48 + (dataLen * 2)
            if (rxBuf[key].length() < totalHex) break

            byte[] data = (dataLen > 0)
                ? rxBuf[key].substring(48, totalHex).decodeHex()
                : new byte[0]

            rxBuf[key] = rxBuf[key].substring(totalHex)
            handleAdbMessage(cmd, arg0, arg1, data)
        }
    }

    private void handleAdbMessage(int cmd, int arg0, int arg1, byte[] data) {
        switch (cmd) {

            case CMD_CNXN:
                logD "← CNXN: authenticated"
                unschedule("authPubkeyTimeout")
                unschedule("retryAfterAuthTimeout")
                state.waitingForUserAuth = false
                state.connState = "CONNECTED"
                sendEvent(name: "adbStatus", value: "Connected")
                scheduleKeepAlive()
                executePendingShell()
                break

            case CMD_AUTH:
                if (arg0 == AUTH_TOKEN) {
                    if (state.connState == "AUTH_WAIT") {
                        // 1ª tentativa: assinar o token com a chave privada
                        // Se a chave já for confiável, a TV responde com CNXN (sem diálogo)
                        logD "← AUTH TOKEN → trying RSA signature"
                        state.connState = "AUTH_PUBKEY_WAIT"
                        byte[] sig = signWithPrivateKey(data)
                        if (sig) {
                            sendAdbMsg(CMD_AUTH, AUTH_SIGNATURE, 0, sig)
                            logD "→ AUTH SIGNATURE"
                            // Sem timeout aqui: se aceito, CNXN chega rapidamente e cancela;
                            // se rejeitado, chega outro AUTH_TOKEN e o else abaixo agenda o timeout
                        } else {
                            // Sem chave privada: vai direto para chave pública
                            state.waitingForUserAuth = true
                            state.pendingShellCmd = null
                            sendEvent(name: "adbStatus", value: "waiting_auth")
                            log.warn "[FireTV] Chave não encontrada. Selecione 'SEMPRE PERMITIR' na tela da TV!"
                            sendAdbMsg(CMD_AUTH, AUTH_RSAPUBLICKEY, 0, state.adbPublicKey.bytes)
                            logD "→ AUTH RSAPUBLICKEY (without private key)"
                            runIn(90, "authPubkeyTimeout")
                        }
                    } else {
                        // Assinatura rejeitada → chave não reconhecida → enviar chave pública
                        // A TV mostrará o diálogo "Autorizar ADB?" somente desta vez
                        logD "← AUTH TOKEN (signature rejected) → sending public key"
                        state.waitingForUserAuth = true
                        state.pendingShellCmd = null
                        sendEvent(name: "adbStatus", value: "aguardando_autorizacao")
                        log.warn "[FireTV] Chave RSA não reconhecida pela TV. Selecione 'SEMPRE PERMITIR' na tela!"
                        sendAdbMsg(CMD_AUTH, AUTH_RSAPUBLICKEY, 0, state.adbPublicKey.bytes)
                        logD "→ AUTH RSAPUBLICKEY"
                        runIn(90, "authPubkeyTimeout")
                    }
                }
                break

            case CMD_OKAY:
                if (state.connState == "SHELL_OPENING") {
                    state.remoteId   = arg0
                    state.connState  = "SHELL_READY"
                    state.promptCount = 0
                    logD "← OKAY shell aberto (remoteId=${state.remoteId})"
                    String toSend = state.pendingShellCmd
                    if (toSend) {
                        state.pendingShellCmd = null
                        sendAdbMsg(CMD_WRTE, LOCAL_ID, state.remoteId as int, (toSend + "\n").bytes)
                        logD "→ WRTE: ${toSend}"
                    }
                } else if (state.connState == "SHELL_READY") {
                    logD "← OKAY (flow ctrl)"
                }
                break

            case CMD_WRTE:
                sendAdbMsg(CMD_OKAY, LOCAL_ID, arg0, new byte[0])
                if (data && data.length > 0) {
                    String resp = new String(data).replaceAll(/[\x00-\x08\x0b-\x1f]/, "").trim()
                    if (resp) {
                        logD "← data: ${resp.take(200)}"
                        if (state.awaitCurrentApp && (
                                resp.contains("mCurrentFocus") ||
                                resp.contains("mFocusedApp") ||
                                resp.contains("topResumedActivity")
                            )) {

                            def m = (resp =~ /([a-zA-Z0-9_.]+)\/([a-zA-Z0-9_.$]+)/)

                            if (m) {
                                state.awaitCurrentApp = false

                                String newApp = m[0][1]

                                Map friendlyNames = [
                                    "com.netflix.ninja"                 : "Netflix",
                                    "com.amazon.firebat"                : "Prime Video",
                                    "com.disney.disneyplus"             : "Disney+",
                                    "com.hbo.hbonow"                    : "HBO Max",
                                    "com.amazon.firetv.youtube"         : "YouTube",
                                    "com.apple.atve.amazon.appletv"     : "Apple TV",
                                    "com.spotify.music"                 : "Spotify",
                                    "com.plexapp.android"               : "Plex",
                                    "tv.twitch.android.app"             : "Twitch"
                                ]

                                String friendlyName = friendlyNames[newApp] ?: newApp

                                if (device.currentValue("currentApp") != newApp) {

                                    sendEvent(name: "currentApp", value: newApp)

                                    sendEvent(
                                        name: "currentAppName",
                                        value: friendlyName
                                    )

                                    log.info "[FireTV] Current app changed to: ${friendlyName} (${newApp})"
                                }                            

                                
                                
                            } else {
                                logD "Ignoring answer without package/valid activity: ${resp.take(200)}"
                            }
                        }
                        // Detecta prompt do shell ($ ou #) e conta ocorrências:
                        // 1ª = prompt inicial (antes do comando), 2ª = após execução → fecha
                        if (state.connState == "SHELL_READY" &&
                                (resp.endsWith('$') || resp.endsWith('#'))) {
                            state.promptCount = (state.promptCount ?: 0) + 1
                            logD "Prompt #${state.promptCount}"
                            if (state.promptCount >= 2) {
                                logD "→ Command sent successfully, closing shell"
                                state.connState = "SHELL_CLOSING"
                                sendAdbMsg(CMD_CLSE, LOCAL_ID, state.remoteId as int, new byte[0])
                            }
                        }
                    }
                }
                break

            case CMD_CLSE:
                logD "← CLSE (shell fechado)"
                if (state.connState != "SHELL_CLOSING") {
                    sendAdbMsg(CMD_CLSE, LOCAL_ID, arg0, new byte[0])
                }
                state.remoteId    = 0
                state.promptCount = 0
                unschedule("forceCloseShell")
                // Mantém a conexão TCP viva — só autentica uma vez
                state.connState   = "CONNECTED"
                // Executa comando que chegou enquanto o shell estava ocupado
                if (state.pendingShellCmd) {
                    executePendingShell()
                }
                break

            default:
                logD "← cmd desconhecido: 0x${Integer.toHexString(cmd & 0xFFFFFFFF)}"
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // EXECUÇÃO DE SHELL
    // ═══════════════════════════════════════════════════════════════════════════════

    private void executePendingShell() {
        if (!state.pendingShellCmd) return
        logD "→ OPEN shell: (cmd: ${state.pendingShellCmd})"
        state.connState   = "SHELL_OPENING"
        state.promptCount = 0
        sendAdbMsg(CMD_OPEN, LOCAL_ID, 0, "shell:\0".bytes)
        runIn(12, "forceCloseShell")  // fallback: fecha se trava
    }

    def forceCloseShell() {
        if (state.connState in ["SHELL_OPENING", "SHELL_READY", "SHELL_CLOSING"]) {
            log.warn "[FireTV] Shell timeout — fechando canal shell (TCP mantido)"
            state.pendingShellCmd = null
            if (state.remoteId) {
                try { sendAdbMsg(CMD_CLSE, LOCAL_ID, state.remoteId as int, new byte[0]) } catch (ex) { /* ignora */ }
            }
            state.connState   = "CONNECTED"
            state.remoteId    = 0
            state.promptCount = 0
        }
    }

    def authPubkeyTimeout() {
        if (state.connState == "AUTH_PUBKEY_WAIT") {
            log.warn "[FireTV] Auth timeout — TV não respondeu. Aguardando 5 min antes de tentar novamente. Selecione 'SEMPRE PERMITIR' na TV!"
            closeSocket()
            // Agenda retry em 5 minutos — dá tempo do usuário autorizar e o driver tentar de novo
            runIn(300, "retryAfterAuthTimeout")
        }
    }

    def retryAfterAuthTimeout() {
        if (state.waitingForUserAuth) {
            log.info "[FireTV] Tentando reconectar após timeout de auth..."
            state.waitingForUserAuth = false
            connectToDevice()
        }
    }

    def sendShell(String shellCmd) {
        logD "shell: ${shellCmd}"
        state.pendingShellCmd = shellCmd
        switch (state.connState) {
            case "IDLE":
                // Comando manual do usuário — limpa o bloqueio de auth e tenta conectar
                if (state.waitingForUserAuth) {
                    log.info "[FireTV] Forçando nova tentativa de conexão. Certifique-se que a tela da TV está ligada!"
                    state.waitingForUserAuth = false
                    unschedule("retryAfterAuthTimeout")
                }
                connectToDevice()
                break
            case "CONNECTED":
                executePendingShell()
                break
            case "SHELL_OPENING":
            case "SHELL_READY":
            case "SHELL_CLOSING":
                // Shell ocupado: comando ficará em fila e será executado após CMD_CLSE
                logD "Shell busy (${state.connState}), in queue"
                break
            default:
                logD "Connecting (${state.connState}), na fila"
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // CAPABILITIES
    // ═══════════════════════════════════════════════════════════════════════════════

    def on()      { wakeUp();      sendEvent(name: "switch", value: "on") }
    def off()     { sleepDevice(); sendEvent(name: "switch", value: "off") }
    def refresh() { getCurrentApp() }

    // ─── Key Events ───────────────────────────────────────────────────────────────
    // ─── Key Events ───────────────────────────────────────────────────────────────
    def home()          { keyEvent(KEY.HOME) }
    def back()          { keyEvent(KEY.BACK) }
    def menu()          { keyEvent(KEY.MENU) }
    def wakeUp()        { keyEvent(KEY.WAKEUP) }
    def sleepDevice()   { keyEvent(KEY.SLEEP) }
    def select()        { keyEvent(KEY.DPAD_CENTER) }
    def dpadUp()        { keyEvent(KEY.DPAD_UP) }
    def dpadDown()      { keyEvent(KEY.DPAD_DOWN) }
    def dpadLeft()      { keyEvent(KEY.DPAD_LEFT) }
    def dpadRight()     { keyEvent(KEY.DPAD_RIGHT) }
    def arrowLeft()     { dpadLeft() }
    def arrowRight()    { dpadRight() }
    def arrowUp()       { dpadUp() }
    def arrowDown()     { dpadDown() }
    def volumeUp()      { keyEvent(KEY.VOLUME_UP) }
    def volumeDown()    { keyEvent(KEY.VOLUME_DOWN) }
    def mute()          { keyEvent(KEY.VOLUME_MUTE) }
    def play()          { keyEvent(KEY.PLAY) }
    def pause()         { keyEvent(KEY.PAUSE) }
    def playPause()     { keyEvent(KEY.PLAY_PAUSE) }
    def stop()          { keyEvent(KEY.STOP) }
    def fastForward()   { keyEvent(KEY.FF) }
    def fastBack()      { rewind() }
    def rewind()        { keyEvent(KEY.REWIND) }
    def nextTrack()     { keyEvent(KEY.NEXT) }
    def previousTrack() { keyEvent(KEY.PREV) }
    def enter()         { select() }
    def numericKeyPad() { keyEvent(7) }
    def guide()         { keyEvent(172) }
    def sourceSetOSD()  { keyEvent(178) }
    def sourceToggle()  { keyEvent(178) }
    def channelList()   { keyEvent(229) }
    def channelUp()     { keyEvent(166) }
    def channelDown()   { keyEvent(167) }
    def channelSet(channel) {
        channel.toString().each { digit ->
            if (digit >= "0" && digit <= "9") {
                keyEvent(7 + digit.toInteger())
                pauseExecution(150)
            }
        }
        keyEvent(KEY.ENTER)
    }
    def previousChannel() { keyEvent(229) }
    def exit()            { keyEvent(KEY.ESCAPE) }
    def Return()          { back() }

    def keyEvent(int code)  { sendShell("input keyevent ${code}") }
    def sendKeyEvent(code)  { keyEvent(code as int) }
    def sendKey(String key) {
        switch (key?.toUpperCase()) {
            case "HOME": home(); break
            case "MENU": menu(); break
            case "RETURN":
            case "BACK": Return(); break
            case "EXIT": exit(); break
            case "ENTER":
            case "SELECT": enter(); break
            case "LEFT": arrowLeft(); break
            case "RIGHT": arrowRight(); break
            case "UP": arrowUp(); break
            case "DOWN": arrowDown(); break
            case "PLAY": play(); break
            case "PAUSE": pause(); break
            case "STOP": stop(); break
            case "FF":
            case "FF_":
            case "FASTFORWARD": fastForward(); break
            case "REWIND":
            case "REW":
            case "FASTBACK": fastBack(); break
            case "VOLUP":
            case "VOLUMEUP": volumeUp(); break
            case "VOLDOWN":
            case "VOLUMEDOWN": volumeDown(); break
            case "MUTE": mute(); break
            case "CHUP":
            case "CHANNELUP": channelUp(); break
            case "CHDOWN":
            case "CHANNELDOWN": channelDown(); break
            default:
                log.warn "[FireTV] sendKey not mapped: ${key}"
        }
    }


    // ─── Apps ─────────────────────────────────────────────────────────────────────
    def launchApp(String pkg)  { 
        sendShell("monkey -p ${pkg} -c android.intent.category.LAUNCHER 1")  
        refreshCurrentAppSoon()
    }
    def launchNetflix()        { launchApp(APPS.netflix)  }
    def launchYouTube()        { launchApp(APPS.youtube)  }
    def launchDisneyPlus()     { launchApp(APPS.disney) }

    // LandingActivity não é exportada; usa deep link primevideo:// roteado pelo Android
    // Prime Video (Fire TV usa LEANBACK_LAUNCHER + DeepLinkRoutingActivity)
    def launchPrimeVideo() {
        sendShell("am start -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -n com.amazon.firebat/com.amazon.firebatcore.deeplink.DeepLinkRoutingActivity")
        refreshCurrentAppSoon()
    }
    def launchHBOMax()     { sendShell("am start -n com.hbo.hbonow/com.wbd.beam.BeamActivity"); refreshCurrentAppSoon() }
    def launchAppleTV()    { sendShell("am start -n com.apple.atve.amazon.appletv/.MainActivity"); refreshCurrentAppSoon() }

    def appOpenByName(String appName) {

        switch(appName?.trim()) {

            case "Netflix":
                launchNetflix()
                break

            case "PrimeVideo":
            case "Prime Video":
            case "Prime%20Video":
                launchPrimeVideo()
                break

            case "Disney":
            case "Disney+":
                launchDisneyPlus()
                break

            case "YouTube":
                launchYouTube()
                break

            case "AppleTV":
                launchAppleTV()
                break

            case "HBOMax":
            case "HBO Max":
            case "HBO%20Max":
                launchHBOMax()
                break

            default:
                log.warn "[FireTV] appOpenByName: unkown app: ${appName}"
                break
        }
        refreshCurrentAppSoon()
    }

    // ─── Shell & Status ───────────────────────────────────────────────────────────
    def sendShellCommand(String cmd) { sendShell(cmd) }

    def getCurrentApp() {
        state.awaitCurrentApp = true
        sendShell("dumpsys window 2>/dev/null | grep -m1 -E 'mCurrentFocus|mFocusedApp|topResumedActivity'")
    }

    def pollCurrentApp() {
        if (!state.waitingForUserAuth) {
            getCurrentApp()
        } else {
            logD "Poll pausado — aguardando autorização na tela da TV"
        }
        runIn(poltime, "pollCurrentApp")
    }

    private void refreshCurrentAppSoon() {
        runIn(3, "getCurrentApp")
    }

    // ─── TCP Keep-Alive ───────────────────────────────────────────────────────────

    private void scheduleKeepAlive() {
        unschedule("tcpKeepAlive")
        runIn(240, "tcpKeepAlive")  // 4 minutos
    }

    def tcpKeepAlive() {
        if (state.connState == "CONNECTED" && !state.pendingShellCmd) {
            logD "TCP keep-alive ping"
            sendShell("echo 1")
            scheduleKeepAlive()
        } else if (state.connState == "CONNECTED") {
            // Há atividade em andamento — a conexão já está viva, só reagenda
            scheduleKeepAlive()
        }
        // Se IDLE, a próxima poll cuida da reconexão
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════════

    private void logD(String msg) {
        if (settings.logEnable) log.debug "[FireTV] ${msg}"
    }
