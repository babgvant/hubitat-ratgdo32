/** Direct local HTTP/SSE driver for current ratgdo/homekit-ratgdo32 firmware. */
import groovy.transform.Field
import java.security.MessageDigest

@Field static final String DRIVER_VERSION = "0.1.2"

metadata {
    definition(name: "ratgdo32 Direct HTTP Garage Door", namespace: "babgvant", author: "Andrew Van Til",
            importUrl: "https://raw.githubusercontent.com/babgvant/hubitat-ratgdo32/main/ratgdo32-http.groovy") {
        capability "GarageDoorControl"
        capability "ContactSensor"
        capability "Refresh"
        capability "Configuration"
        capability "Initialize"
        capability "HealthCheck"
        attribute "position", "number"
        attribute "motion", "enum", ["opening", "closing", "stopped", "unknown"]
        attribute "obstruction", "enum", ["clear", "detected", "unknown"]
        attribute "controllerStatus", "enum", ["online", "offline"]
        attribute "streamStatus", "enum", ["connecting", "connected", "disconnected"]
        attribute "lastSeen", "string"
        attribute "rawEncoderPosition", "number"
        attribute "encoderStatus", "enum", ["disabled", "learning", "calibrated", "unknown"]
        attribute "firmwareVersion", "string"
        attribute "wifiSignal", "string"
        attribute "authenticationRequired", "enum", ["yes", "no", "unknown"]
        attribute "authenticationStatus", "enum", ["disabled", "credentialsMissing", "challenging", "ready", "failed", "unknown"]
        attribute "driverVersion", "string"
    }
    preferences {
        input name: "ipAddress", type: "text", title: "ratgdo32 IP address or hostname", required: true
        input name: "httpPort", type: "number", title: "HTTP port", defaultValue: 80, range: "1..65535", required: true
        input name: "httpUsername", type: "text", title: "ratgdo32 HTTP username", defaultValue: "admin", required: false
        input name: "httpPassword", type: "password", title: "ratgdo32 HTTP password", required: false
        input name: "staleMinutes", type: "number", title: "Mark offline after no updates (minutes)", defaultValue: 5, range: "2..1440", required: true
        input name: "positionPollSeconds", type: "number", title: "Position polling while moving (seconds)", defaultValue: 2, range: "1..10", required: true
        input name: "infoLogging", type: "bool", title: "Enable informational logging", defaultValue: true
        input name: "debugLogging", type: "bool", title: "Enable debug logging (auto-disables after 30 minutes)", defaultValue: false
        input name: "traceLogging", type: "bool", title: "Enable raw HTTP/SSE logging (auto-disables after 30 minutes)", defaultValue: false
    }
}

void installed() { initializeAttributes(); log.info "${device.displayName}: installed driver ${DRIVER_VERSION}" }

void updated() {
    logInfo "preferences updated"
    unschedule()
    closeEventStream()
    clearDigestState()
    initializeAttributes()
    if (settings.debugLogging || settings.traceLogging) runIn(1800, "disableVerboseLogging", [overwrite: true])
    runIn(1, "initialize", [overwrite: true])
}

void configure() { initialize() }

void initialize() { connectDevice(true) }

private void connectDevice(Boolean cancelReconnect) {
    initializeAttributes()
    if (cancelReconnect) unschedule("reconnect")
    unschedule("pollPosition")
    if (!configurationValid()) return
    closeEventStream()
    emit("streamStatus", "connecting")
    refresh()
    requestEventSubscription()
    scheduleHealthCheck()
}

void uninstalled() { unschedule(); closeEventStream() }

void refresh() {
    if (!configurationValid()) return
    Map params = [uri: baseUri(), path: "/status.json", contentType: "application/json", timeout: 8]
    logTrace "HTTP GET ${baseUri()}/status.json"
    try { asynchttpGet("statusCallback", params) }
    catch (Exception e) { communicationFailure("status request failed", e) }
}

void ping() { refresh() }
void open() { issueMovementCommand("open") }
void close() { issueMovementCommand("close") }

void statusCallback(response, Map data) {
    Integer status = response?.status as Integer
    if (status != 200) { handleHttpError("status", status); return }
    try {
        Map body = response.json as Map
        if (body == null) throw new IllegalArgumentException("empty JSON response")
        logTrace "HTTP <= /status.json ${body}"
        handleStatus(body, false)
    } catch (Exception e) { log.warn "${device.displayName}: invalid /status.json response ignored: ${e.message}" }
}

void commandCallback(response, Map data) {
    Integer status = response?.status as Integer
    if (status in [200, 204]) {
        if (data?.authenticated) emit("authenticationStatus", "ready")
        logInfo "${data?.command ?: 'door'} command accepted by ratgdo32"
        runIn(1, "refresh", [overwrite: true])
    } else if (status == 401) {
        Map challenge = captureDigestChallenge(response)
        if (challenge && data?.nonceRetry != true && credentialsConfigured()) {
            logInfo "digest nonce rejected or refreshed; retrying authentication once"
            sendDoorCommand(data.command.toString(), data.value.toString(), true, true)
        } else {
            emit("authenticationStatus", "failed")
            log.error "${device.displayName}: command authentication failed; no physical command was accepted"
        }
    } else handleHttpError("command", status)
}

void digestProbeCallback(response, Map data) {
    state.digestProbePending = false
    Integer status = response?.status as Integer
    if (status == 401) {
        if (!captureDigestChallenge(response)) {
            emit("authenticationStatus", "failed")
            log.error "${device.displayName}: unable to parse ratgdo32 Digest challenge"
        }
    } else if (status in [200, 204]) {
        // A no-argument /setgdo request performs no action.
        clearDigestState()
        emit("authenticationRequired", "no")
        emit("authenticationStatus", "disabled")
    } else {
        handleHttpError("authentication probe", status)
    }
}

void subscriptionCallback(response, Map data) {
    Integer status = response?.status as Integer
    if (status != 200) { handleHttpError("event subscription", status); scheduleReconnect(); return }
    String path = response.data?.toString()?.trim()
    if (!path?.startsWith("/")) {
        log.warn "${device.displayName}: invalid event subscription path '${path}'"
        scheduleReconnect(); return
    }
    String url = "${baseUri()}${path}?id=${urlEncode(subscriptionId())}"
    logDebug "connecting event stream ${url}"
    try {
        interfaces.eventStream.connect(url, [pingInterval: 10, readTimeout: 75,
                headers: ["Accept": "text/event-stream"], rawData: false])
    } catch (Exception e) { communicationFailure("event-stream connection failed", e); scheduleReconnect() }
}

void parse(String message) {
    if (!message?.trim()) return
    logTrace "SSE <= ${message}"
    try { handleStatus(parseJson(message) as Map, true) }
    catch (Exception e) { log.warn "${device.displayName}: malformed SSE message ignored: ${e.message}" }
}

void eventStreamStatus(String message) {
    logDebug "event stream status: ${message}"
    if (message?.startsWith("START:")) {
        state.reconnectAttempt = 0
        emit("streamStatus", "connected")
        noteSeen(); refresh()
    } else if (message?.startsWith("STOP:") || message?.startsWith("ERROR:")) {
        emit("streamStatus", "disconnected")
        markControllerOffline(); scheduleReconnect()
    }
}

void healthCheck() {
    Long lastSeen = state.lastSeenEpoch as Long
    Long staleMs = asInteger(settings.staleMinutes, 5) * 60L * 1000L
    if (!lastSeen || now() - lastSeen > staleMs) {
        log.warn "${device.displayName}: ratgdo32 state is stale"
        markControllerOffline()
        if (device.currentValue("streamStatus") != "connecting") scheduleReconnect()
    }
}

void reconnect() { connectDevice(false) }
void pollPosition() { if (device.currentValue("motion") in ["opening", "closing"]) refresh() }

private void requestEventSubscription() {
    Map params = [uri: baseUri(), path: "/rest/events/subscribe",
            query: [id: subscriptionId(), heartbeat: "30"], contentType: "text/plain", timeout: 8]
    logTrace "HTTP GET ${baseUri()}/rest/events/subscribe"
    try { asynchttpGet("subscriptionCallback", params) }
    catch (Exception e) { communicationFailure("event subscription failed", e); scheduleReconnect() }
}

private void issueMovementCommand(String command) {
    String door = device.currentValue("door")?.toString()
    String motion = device.currentValue("motion")?.toString()
    Boolean partialStopped = door == "unknown" && motion == "stopped" && asNumber(device.currentValue("rawEncoderPosition")) != null
    if (device.currentValue("controllerStatus") != "online") { log.warn "${device.displayName}: rejected ${command}; controller is offline"; return }
    String authRequired = device.currentValue("authenticationRequired")?.toString()
    if (authRequired == "yes" && device.currentValue("authenticationStatus") != "ready") {
        log.warn "${device.displayName}: rejected ${command}; Digest authentication is not ready"
        primeDigestAuthentication()
        return
    }
    if (!(authRequired in ["yes", "no"])) { log.warn "${device.displayName}: rejected ${command}; authentication state is unknown"; return }
    if ((door == "unknown" && !partialStopped) || motion == "unknown") { log.warn "${device.displayName}: rejected ${command}; door state is unknown"; return }
    if (motion in ["opening", "closing"]) { log.warn "${device.displayName}: rejected ${command}; door is already moving (${motion})"; return }
    if ((command == "open" && door == "open") || (command == "close" && door == "closed")) { logInfo "ignored ${command}; already at endpoint"; return }

    String value = command == "open" ? "1" : "0"
    sendDoorCommand(command, value, authRequired == "yes", false)
}

private void sendDoorCommand(String command, String value, Boolean authenticated, Boolean nonceRetry) {
    Map<String, String> headers = [:]
    if (authenticated) {
        String authorization = buildDigestAuthorization("POST", "/setgdo")
        if (!authorization) {
            emit("authenticationStatus", "failed")
            log.error "${device.displayName}: could not build Digest authorization; command was not sent"
            return
        }
        headers.Authorization = authorization
    }
    Map params = [uri: baseUri(), path: "/setgdo", body: "garageDoorState=${value}",
            headers: headers, requestContentType: "application/x-www-form-urlencoded", timeout: 10]
    logDebug "HTTP POST /setgdo garageDoorState=${value}"
    try { asynchttpPost("commandCallback", params,
            [command: command, value: value, authenticated: authenticated, nonceRetry: nonceRetry]) }
    catch (Exception e) {
        log.error "${device.displayName}: ${command} request failed and was not retried: ${e.message}"
        markControllerOffline()
    }
}

private void handleStatus(Map update, Boolean fromEventStream) {
    if (!update) return
    noteSeen()
    if (update.containsKey("passwordRequired")) {
        Boolean required = asBoolean(update.passwordRequired)
        emit("authenticationRequired", required ? "yes" : "no")
        if (required) {
            if (!credentialsConfigured()) emit("authenticationStatus", "credentialsMissing")
            else if (!digestChallengeAvailable()) primeDigestAuthentication()
        } else {
            clearDigestState()
            emit("authenticationStatus", "disabled")
        }
    }
    if (update.firmwareVersion != null) emit("firmwareVersion", update.firmwareVersion.toString())
    if (update.wifiRSSI != null) emit("wifiSignal", update.wifiRSSI.toString())
    if (update.containsKey("garageObstructed")) emit("obstruction", asBoolean(update.garageObstructed) ? "detected" : "clear")

    String reportedDoor = update.garageDoorState?.toString()?.toLowerCase()
    BigDecimal rawSteps = update.containsKey("encSteps") ? asNumber(update.encSteps) : null
    Boolean encoderEnabled = update.containsKey("encoderEnabled") ? asBoolean(update.encoderEnabled) : null
    if (encoderEnabled == false) emit("encoderStatus", "disabled")
    else if (encoderEnabled == true && device.currentValue("encoderStatus") in [null, "disabled", "unknown"]) emit("encoderStatus", endpointsCalibrated() ? "calibrated" : "learning")

    if (rawSteps != null) {
        state.rawEncoderPosition = rawSteps.toPlainString()
        emit("rawEncoderPosition", rawSteps.stripTrailingZeros().toPlainString())
        learnEndpoint(reportedDoor ?: state.reportedDoor?.toString(), rawSteps)
        publishPosition(rawSteps, reportedDoor ?: state.reportedDoor?.toString())
    }
    if (reportedDoor != null) {
        parseDoorState(reportedDoor)
        if (reportedDoor in ["opening", "closing"]) schedulePositionPoll()
        else if (fromEventStream && rawSteps == null) runIn(1, "refresh", [overwrite: true])
    }
}

private void parseDoorState(String reported) {
    state.reportedDoor = reported
    switch (reported) {
        case "open": emit("door", "open"); emit("motion", "stopped"); emit("contact", "open"); emit("position", 100, "%"); break
        case "closed": emit("door", "closed"); emit("motion", "stopped"); emit("contact", "closed"); emit("position", 0, "%"); break
        case "opening": emit("door", "opening"); emit("motion", "opening"); emit("contact", "open"); break
        case "closing": emit("door", "closing"); emit("motion", "closing"); emit("contact", "open"); break
        case "stopped": emit("door", "unknown"); emit("motion", "stopped"); emit("contact", "open"); break
        default: emit("door", "unknown"); emit("motion", "unknown"); emit("contact", "open"); log.warn "${device.displayName}: unknown garageDoorState '${reported}'"
    }
}

private void learnEndpoint(String reported, BigDecimal raw) {
    if (reported == "closed") state.encoderClosedSteps = raw.toPlainString()
    else if (reported == "open") state.encoderOpenSteps = raw.toPlainString()
    if (endpointsCalibrated()) emit("encoderStatus", "calibrated")
    else if (device.currentValue("encoderStatus") != "disabled") emit("encoderStatus", "learning")
}

private void publishPosition(BigDecimal raw, String reported) {
    if (reported == "closed") { emit("position", 0, "%"); return }
    if (reported == "open") { emit("position", 100, "%"); return }
    BigDecimal closed = asNumber(state.encoderClosedSteps), open = asNumber(state.encoderOpenSteps)
    if (closed == null || open == null || open == closed) return
    BigDecimal calculated = ((raw - closed) * 100G) / (open - closed)
    Integer rounded = Math.max(0, Math.min(100, calculated.setScale(0, BigDecimal.ROUND_HALF_UP).intValue()))
    emit("position", rounded, "%")
}

private Boolean endpointsCalibrated() {
    BigDecimal closed = asNumber(state.encoderClosedSteps), open = asNumber(state.encoderOpenSteps)
    closed != null && open != null && closed != open
}

private void schedulePositionPoll() { runIn(asInteger(settings.positionPollSeconds, 2), "pollPosition", [overwrite: true]) }

/**
 * Request a Digest challenge without supplying any setgdo arguments. The
 * firmware loops over zero settings and performs no physical action if auth is
 * disabled; when enabled it returns 401 before entering that loop.
 */
private void primeDigestAuthentication() {
    if (!credentialsConfigured()) {
        emit("authenticationStatus", "credentialsMissing")
        return
    }
    if (state.digestProbePending) return
    state.digestProbePending = true
    emit("authenticationStatus", "challenging")
    Map params = [uri: baseUri(), path: "/setgdo", body: "",
            requestContentType: "application/x-www-form-urlencoded", timeout: 8]
    logDebug "requesting Digest authentication challenge"
    try { asynchttpPost("digestProbeCallback", params) }
    catch (Exception e) {
        state.digestProbePending = false
        emit("authenticationStatus", "failed")
        communicationFailure("Digest challenge request failed", e)
    }
}

private Map captureDigestChallenge(response) {
    String header = responseHeader(response, "WWW-Authenticate")
    if (!header?.toLowerCase()?.startsWith("digest ")) return null
    Map<String, String> challenge = parseDigestChallenge(header.substring(7))
    if (!challenge.realm || !challenge.nonce) return null

    String algorithm = (challenge.algorithm ?: "MD5").toUpperCase()
    if (!(algorithm in ["MD5", "MD5-SESS"])) {
        log.error "${device.displayName}: unsupported Digest algorithm '${algorithm}'"
        return null
    }
    String qop = selectDigestQop(challenge.qop)
    if (challenge.qop && !qop) {
        log.error "${device.displayName}: Digest challenge does not offer qop=auth"
        return null
    }
    challenge.algorithm = algorithm
    challenge.qop = qop
    state.digestChallenge = challenge
    state.digestNonceCount = 0
    state.digestProbePending = false
    emit("authenticationRequired", "yes")
    emit("authenticationStatus", "ready")
    logInfo "Digest authentication challenge accepted"
    return challenge
}

private String buildDigestAuthorization(String method, String requestUri) {
    Map challenge = state.digestChallenge as Map
    String username = settings.httpUsername?.toString()
    String password = settings.httpPassword?.toString()
    if (!challenge?.realm || !challenge?.nonce || !username || password == null) return null

    Integer count = ((state.digestNonceCount ?: 0) as Integer) + 1
    state.digestNonceCount = count
    String nc = String.format("%08x", count)
    String cnonce = UUID.randomUUID().toString().replace("-", "")
    String ha1 = md5Hex("${username}:${challenge.realm}:${password}")
    if (challenge.algorithm == "MD5-SESS") ha1 = md5Hex("${ha1}:${challenge.nonce}:${cnonce}")
    String ha2 = md5Hex("${method}:${requestUri}")
    String responseHash = challenge.qop ?
            md5Hex("${ha1}:${challenge.nonce}:${nc}:${cnonce}:${challenge.qop}:${ha2}") :
            md5Hex("${ha1}:${challenge.nonce}:${ha2}")

    List<String> parts = [
            "username=\"${escapeDigest(username)}\"",
            "realm=\"${escapeDigest(challenge.realm.toString())}\"",
            "nonce=\"${escapeDigest(challenge.nonce.toString())}\"",
            "uri=\"${escapeDigest(requestUri)}\"",
            "response=\"${responseHash}\"",
            "algorithm=${challenge.algorithm}"
    ]
    if (challenge.opaque) parts << "opaque=\"${escapeDigest(challenge.opaque.toString())}\""
    if (challenge.qop) {
        parts << "qop=${challenge.qop}"
        parts << "nc=${nc}"
        parts << "cnonce=\"${cnonce}\""
    }
    "Digest ${parts.join(', ')}"
}

private static Map<String, String> parseDigestChallenge(String value) {
    Map<String, String> result = [:]
    def matcher = value =~ /([A-Za-z0-9_-]+)\s*=\s*(?:"([^"]*)"|([^,\s]+))/
    matcher.each { match -> result[match[1].toString().toLowerCase()] = (match[2] ?: match[3])?.toString() }
    result
}

private static String selectDigestQop(Object offered) {
    if (!offered) return null
    offered.toString().split(',').collect { it.trim().toLowerCase() }.find { it == "auth" }
}

private static String responseHeader(response, String wantedName) {
    Map headers = response?.headers as Map
    def entry = headers?.find { key, ignored -> key?.toString()?.equalsIgnoreCase(wantedName) }
    Object value = entry?.value
    if (value instanceof Collection) value = value ? value.first() : null
    value?.toString()
}

private static String md5Hex(String value) {
    MessageDigest.getInstance("MD5").digest(value.getBytes("UTF-8")).encodeHex().toString()
}

private static String escapeDigest(String value) { value.replace("\\", "\\\\").replace("\"", "\\\"") }

private Boolean credentialsConfigured() {
    settings.httpUsername?.toString()?.trim() && settings.httpPassword != null && settings.httpPassword.toString().length() > 0
}

private Boolean digestChallengeAvailable() {
    Map challenge = state.digestChallenge as Map
    challenge?.realm && challenge?.nonce
}

private void clearDigestState() {
    state.remove("digestChallenge")
    state.remove("digestNonceCount")
    state.remove("digestProbePending")
}

private void handleHttpError(String operation, Integer status) {
    if (status in [401, 403]) {
        emit("authenticationRequired", "yes")
        log.error "${device.displayName}: ${operation} rejected with HTTP ${status}; verify the configured Digest credentials"
    } else { log.warn "${device.displayName}: ${operation} returned HTTP ${status ?: 'unknown'}"; markControllerOffline() }
}

private void communicationFailure(String context, Exception e) { log.warn "${device.displayName}: ${context}: ${e.message}"; markControllerOffline() }

private void noteSeen() {
    state.lastSeenEpoch = now()
    emit("lastSeen", new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX", location.timeZone))
    emit("controllerStatus", "online")
}

private void markControllerOffline() { emit("controllerStatus", "offline"); emit("motion", "unknown") }

private void scheduleReconnect() {
    Integer attempt = ((state.reconnectAttempt ?: 0) as Integer) + 1
    state.reconnectAttempt = Math.min(attempt, 6)
    Integer delay = Math.min(10 * (1 << Math.min(attempt - 1, 5)), 300)
    logInfo "scheduling direct connection retry in ${delay} seconds"
    runIn(delay, "reconnect", [overwrite: true])
}

private void scheduleHealthCheck() { runEvery5Minutes("healthCheck"); emit("checkInterval", Math.max(asInteger(settings.staleMinutes, 5) * 60, 300)) }

private void closeEventStream() {
    try { interfaces.eventStream.close() } catch (ignored) { }
    emit("streamStatus", "disconnected")
}

private String baseUri() { "http://${settings.ipAddress?.toString()?.trim()}:${asInteger(settings.httpPort, 80)}" }
private String subscriptionId() { "hubitat-${device.id}" }

private Boolean configurationValid() {
    if (!settings.ipAddress?.toString()?.trim()) { log.error "${device.displayName}: ratgdo32 IP address or hostname is required"; return false }
    true
}

private void initializeAttributes() {
    emit("driverVersion", DRIVER_VERSION)
    if (device.currentValue("door") == null) emit("door", "unknown")
    if (device.currentValue("contact") == null) emit("contact", "open")
    if (device.currentValue("motion") == null) emit("motion", "unknown")
    if (device.currentValue("obstruction") == null) emit("obstruction", "unknown")
    if (device.currentValue("controllerStatus") == null) emit("controllerStatus", "offline")
    if (device.currentValue("streamStatus") == null) emit("streamStatus", "disconnected")
    if (device.currentValue("encoderStatus") == null) emit("encoderStatus", "unknown")
    if (device.currentValue("authenticationRequired") == null) emit("authenticationRequired", "unknown")
    if (device.currentValue("authenticationStatus") == null) emit("authenticationStatus", "unknown")
}

void disableVerboseLogging() {
    if (settings.debugLogging || settings.traceLogging) log.warn "${device.displayName}: verbose logging disabled automatically"
    device.updateSetting("debugLogging", [value: "false", type: "bool"])
    device.updateSetting("traceLogging", [value: "false", type: "bool"])
}

private void emit(String name, Object value, String unit = null) {
    if (device.currentValue(name)?.toString() == value?.toString()) return
    Map event = [name: name, value: value]
    if (unit) event.unit = unit
    sendEvent(event)
}

private static Boolean asBoolean(Object value) { value instanceof Boolean ? value : (value?.toString()?.toLowerCase() in ["true", "1", "yes", "on"]) }
private static Integer asInteger(Object value, Integer fallback) { try { value?.toString()?.toInteger() ?: fallback } catch (ignored) { fallback } }
private static BigDecimal asNumber(Object value) { try { value == null ? null : new BigDecimal(value.toString()) } catch (ignored) { null } }
private static String urlEncode(String value) { java.net.URLEncoder.encode(value, "UTF-8") }
private void logInfo(String message) { if (settings.infoLogging != false) log.info "${device.displayName}: ${message}" }
private void logDebug(String message) { if (settings.debugLogging) log.debug "${device.displayName}: ${message}" }
private void logTrace(String message) { if (settings.traceLogging) log.trace "${device.displayName}: ${message}" }
