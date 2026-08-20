/**
 * ratgdo32 ESPHome MQTT garage-door driver for Hubitat Elevation
 *
 * Targets current ratgdo32 ESPHome firmware with the standard ESPHome MQTT
 * component enabled. It intentionally does not implement the retired
 * mqtt-ratgdo (ESP8266) topic contract.
 */
import groovy.transform.Field

@Field static final String DRIVER_VERSION = "1.0.0"
@Field static final Integer DEFAULT_RECONNECT_SECONDS = 30

metadata {
    definition(name: "ratgdo32 ESPHome MQTT Garage Door", namespace: "babgvant", author: "Andrew Van Til",
            importUrl: "https://raw.githubusercontent.com/babgvant/hubitat-ratgdo32/main/ratgdo32-esphome-mqtt.groovy") {
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
        attribute "lastSeen", "string"
        attribute "rawEncoderPosition", "number"
        attribute "driverVersion", "string"
    }

    preferences {
        input name: "brokerHost", type: "text", title: "MQTT broker hostname or IP", required: true
        input name: "brokerPort", type: "number", title: "MQTT broker port", defaultValue: 1883,
                range: "1..65535", required: true
        input name: "useTls", type: "bool", title: "Use TLS (ssl://)", defaultValue: false, required: true
        input name: "mqttUsername", type: "text", title: "MQTT username (optional)", required: false
        input name: "mqttPassword", type: "password", title: "MQTT password (optional)", required: false
        input name: "baseTopic", type: "text", title: "ESPHome topic prefix / node name",
                description: "Example: ratgdo32-a1b2c3", required: true
        input name: "coverObjectId", type: "text", title: "Cover MQTT object ID", defaultValue: "door", required: true
        input name: "obstructionObjectId", type: "text", title: "Obstruction MQTT object ID",
                defaultValue: "obstruction", required: true
        input name: "encoderObjectId", type: "text", title: "Encoder MQTT object ID (blank to disable)",
                defaultValue: "encoder", required: false
        input name: "staleMinutes", type: "number", title: "Mark offline after no messages (minutes)",
                defaultValue: 10, range: "2..1440", required: true
        input name: "infoLogging", type: "bool", title: "Enable informational logging", defaultValue: true
        input name: "debugLogging", type: "bool", title: "Enable debug logging (auto-disables after 30 minutes)", defaultValue: false
        input name: "traceLogging", type: "bool", title: "Enable raw MQTT trace logging (auto-disables after 30 minutes)", defaultValue: false
    }
}

void installed() {
    log.info "${device.displayName}: installed driver ${DRIVER_VERSION}"
    initializeAttributes()
}

void updated() {
    logInfo "preferences updated"
    unschedule()
    interfaces.mqtt.disconnect()
    initializeAttributes()
    if (settings.debugLogging || settings.traceLogging) runIn(1800, "disableVerboseLogging", [overwrite: true])
    runIn(1, "initialize", [overwrite: true])
}

void configure() {
    initialize()
}

void initialize() {
    unschedule("reconnect")
    if (!configurationValid()) return
    if (interfaces.mqtt.isConnected()) {
        subscribeTopics()
        scheduleHealthCheck()
        return
    }

    String scheme = settings.useTls ? "ssl" : "tcp"
    String uri = "${scheme}://${settings.brokerHost.toString().trim()}:${asInteger(settings.brokerPort, 1883)}"
    String clientId = "hubitat-ratgdo32-${device.id}"
    try {
        logInfo "connecting to ${uri} as ${clientId}"
        interfaces.mqtt.connect(uri, clientId, blankToNull(settings.mqttUsername), blankToNull(settings.mqttPassword))
        // Connection completion is asynchronous; mqttClientStatus performs subscriptions.
        runIn(5, "finishConnect", [overwrite: true])
    } catch (Exception e) {
        log.warn "${device.displayName}: MQTT connect failed: ${e.message}"
        markControllerOffline()
        scheduleReconnect()
    }
    scheduleHealthCheck()
}

void finishConnect() {
    if (interfaces.mqtt.isConnected()) {
        state.reconnectAttempt = 0
        subscribeTopics()
    } else {
        scheduleReconnect()
    }
}

void uninstalled() {
    unschedule()
    interfaces.mqtt.disconnect()
}

void parse(String description) {
    Map message
    try {
        message = interfaces.mqtt.parseMessage(description)
    } catch (Exception e) {
        log.warn "${device.displayName}: malformed MQTT message ignored: ${e.message}"
        return
    }

    String topic = message?.topic?.toString()
    String payload = message?.payload?.toString()?.trim()
    if (!topic || payload == null) {
        log.warn "${device.displayName}: MQTT message without topic/payload ignored"
        return
    }
    logTrace "MQTT <= topic=${topic}, payload=${payload}"
    noteSeen()

    Map<String, String> topics = topicMap()
    if (topic == topics.availability) parseAvailability(payload)
    else if (topic == topics.coverState) parseCoverState(payload)
    else if (topic == topics.positionState) parsePosition(payload)
    else if (topic == topics.obstructionState) parseObstruction(payload)
    else if (topics.encoderState && topic == topics.encoderState) parseEncoder(payload)
    else logDebug "ignored unexpected topic ${topic}"
}

void mqttClientStatus(String message) {
    logDebug "MQTT client status: ${message}"
    String normalized = message?.toLowerCase()
    if (normalized?.contains("connected") && !normalized.contains("disconnected")) {
        state.reconnectAttempt = 0
        runIn(1, "finishConnect", [overwrite: true])
    } else if (normalized?.contains("error") || normalized?.contains("lost") || normalized?.contains("disconnected")) {
        markControllerOffline()
        scheduleReconnect()
    }
}

void open() {
    issueMovementCommand("OPEN")
}

void close() {
    issueMovementCommand("CLOSE")
}

void refresh() {
    logInfo "refresh requested"
    if (!interfaces.mqtt.isConnected()) initialize()
    else subscribeTopics() // Retained ESPHome state is delivered again on re-subscription.
}

void ping() {
    refresh()
}

void healthCheck() {
    if (!interfaces.mqtt.isConnected()) {
        markControllerOffline()
        scheduleReconnect()
        return
    }
    Long last = state.lastSeenEpoch as Long
    Long staleMs = asInteger(settings.staleMinutes, 10) * 60L * 1000L
    if (last && now() - last > staleMs) {
        log.warn "${device.displayName}: controller is stale; no MQTT messages for ${settings.staleMinutes ?: 10} minutes"
        markControllerOffline()
    }
}

private void issueMovementCommand(String command) {
    String door = device.currentValue("door")?.toString()
    String motion = device.currentValue("motion")?.toString()
    String controller = device.currentValue("controllerStatus")?.toString()
    BigDecimal position = asNumber(device.currentValue("position"))
    Boolean knownPartial = motion == "stopped" && position != null && position > 0G && position < 100G

    if (controller != "online" || !interfaces.mqtt.isConnected()) {
        log.warn "${device.displayName}: rejected ${command}; controller or broker is offline"
        return
    }
    if ((door == "unknown" && !knownPartial) || motion == "unknown") {
        log.warn "${device.displayName}: rejected ${command}; door state is unknown"
        return
    }
    if (motion in ["opening", "closing"]) {
        log.warn "${device.displayName}: rejected ${command}; door is already moving (${motion})"
        return
    }
    if ((command == "OPEN" && door == "open" && position >= 100G) ||
            (command == "CLOSE" && door == "closed")) {
        logInfo "ignored ${command}; door is already at the requested endpoint"
        return
    }

    publish(topicMap().coverCommand, command)
    // Do not synthesize a movement/final state. Feedback is authoritative.
}

private void parseCoverState(String payload) {
    String value = payload.toLowerCase()
    if (!(value in ["open", "closed", "opening", "closing", "unknown"])) {
        log.warn "${device.displayName}: unknown cover state '${payload}'"
        state.coverState = "unknown"
    } else {
        state.coverState = value
    }
    publishCompositeDoorState()
}

private void parsePosition(String payload) {
    BigDecimal value = asNumber(payload)
    if (value == null || value < 0G || value > 100G) {
        log.warn "${device.displayName}: invalid cover position '${payload}'"
        return
    }
    Integer rounded = value.setScale(0, BigDecimal.ROUND_HALF_UP).intValue()
    state.position = rounded
    emit("position", rounded, "%")
    publishCompositeDoorState()
}

private void publishCompositeDoorState() {
    String reported = state.coverState ?: "unknown"
    Integer position = state.position as Integer
    String door = "unknown"
    String motion = "unknown"

    if (reported == "opening") {
        door = "opening"; motion = "opening"
    } else if (reported == "closing") {
        door = "closing"; motion = "closing"
    } else if (reported == "closed" || position == 0) {
        door = "closed"; motion = "stopped"
    } else if (reported == "open" && position == 100) {
        door = "open"; motion = "stopped"
    } else if (reported == "open" && position != null && position > 0 && position < 100) {
        // ESPHome deliberately reports an idle position-capable cover as "open".
        // GarageDoorControl has no "stopped" value, so preserve truth as unknown.
        door = "unknown"; motion = "stopped"
    }

    emit("door", door)
    emit("motion", motion)
    emit("contact", door == "closed" ? "closed" : "open")
    logDebug "state parsed: cover=${reported}, position=${position}, door=${door}, motion=${motion}"
}

private void parseAvailability(String payload) {
    String value = payload.toLowerCase()
    if (value == "online") {
        emit("controllerStatus", "online")
    } else if (value == "offline") {
        markControllerOffline()
    } else {
        log.warn "${device.displayName}: unknown availability payload '${payload}'"
    }
}

private void parseObstruction(String payload) {
    String value = payload.toUpperCase()
    if (value in ["ON", "DETECTED", "OBSTRUCTED", "TRUE", "1"]) emit("obstruction", "detected")
    else if (value in ["OFF", "CLEAR", "FALSE", "0"]) emit("obstruction", "clear")
    else {
        emit("obstruction", "unknown")
        log.warn "${device.displayName}: unknown obstruction payload '${payload}'"
    }
}

private void parseEncoder(String payload) {
    BigDecimal value = asNumber(payload)
    if (value == null) {
        log.warn "${device.displayName}: invalid encoder payload '${payload}'"
        return
    }
    emit("rawEncoderPosition", value.stripTrailingZeros().toPlainString())
}

private void subscribeTopics() {
    Map<String, String> topics = topicMap()
    [topics.availability, topics.coverState, topics.positionState, topics.obstructionState, topics.encoderState]
            .findAll { it }
            .unique()
            .each { String topic ->
                interfaces.mqtt.subscribe(topic)
                logDebug "subscribed ${topic}"
            }
}

private Map<String, String> topicMap() {
    String root = normalizeTopic(settings.baseTopic)
    String cover = slug(settings.coverObjectId ?: "door")
    String obstruction = slug(settings.obstructionObjectId ?: "obstruction")
    String encoder = settings.encoderObjectId?.toString()?.trim() ? slug(settings.encoderObjectId) : null
    return [
            availability: "${root}/status",
            coverState: "${root}/cover/${cover}/state",
            positionState: "${root}/cover/${cover}/position/state",
            coverCommand: "${root}/cover/${cover}/command",
            obstructionState: "${root}/binary_sensor/${obstruction}/state",
            encoderState: encoder ? "${root}/sensor/${encoder}/state" : null
    ]
}

private void publish(String topic, String payload) {
    try {
        logDebug "MQTT => topic=${topic}, payload=${payload}"
        interfaces.mqtt.publish(topic, payload, 0, false)
    } catch (Exception e) {
        // Never queue or retry a physical command.
        log.error "${device.displayName}: MQTT publish failed; command was not retried: ${e.message}"
        markControllerOffline()
        scheduleReconnect()
    }
}

private void noteSeen() {
    state.lastSeenEpoch = now()
    String timestamp = new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX", location.timeZone)
    emit("lastSeen", timestamp)
}

private void markControllerOffline() {
    emit("controllerStatus", "offline")
    emit("motion", "unknown")
}

private void scheduleReconnect() {
    Integer attempt = ((state.reconnectAttempt ?: 0) as Integer) + 1
    state.reconnectAttempt = Math.min(attempt, 6)
    Integer delay = Math.min(DEFAULT_RECONNECT_SECONDS * (1 << Math.min(attempt - 1, 4)), 480)
    logInfo "scheduling MQTT reconnect in ${delay} seconds"
    runIn(delay, "reconnect", [overwrite: true])
}

void reconnect() {
    if (!interfaces.mqtt.isConnected()) initialize()
    else finishConnect()
}

private void scheduleHealthCheck() {
    runEvery5Minutes("healthCheck")
    emit("checkInterval", Math.max(asInteger(settings.staleMinutes, 10) * 60, 300))
}

private void initializeAttributes() {
    emit("driverVersion", DRIVER_VERSION)
    if (device.currentValue("door") == null) emit("door", "unknown")
    if (device.currentValue("contact") == null) emit("contact", "open")
    if (device.currentValue("motion") == null) emit("motion", "unknown")
    if (device.currentValue("obstruction") == null) emit("obstruction", "unknown")
    if (device.currentValue("controllerStatus") == null) emit("controllerStatus", "offline")
}

private Boolean configurationValid() {
    if (!settings.brokerHost?.toString()?.trim() || !settings.baseTopic?.toString()?.trim()) {
        log.error "${device.displayName}: brokerHost and baseTopic are required"
        return false
    }
    return true
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

private static String normalizeTopic(Object value) {
    value.toString().trim().replaceAll('^/+|/+$', '')
}

private static String slug(Object value) {
    value.toString().trim().toLowerCase().replaceAll('[^a-z0-9]+', '_').replaceAll('^_+|_+$', '')
}

private static String blankToNull(Object value) {
    String result = value?.toString()?.trim()
    result ? result : null
}

private static Integer asInteger(Object value, Integer fallback) {
    try { return value?.toString()?.toInteger() ?: fallback } catch (ignored) { return fallback }
}

private static BigDecimal asNumber(Object value) {
    try { return value == null ? null : new BigDecimal(value.toString()) } catch (ignored) { return null }
}

private void logInfo(String message) {
    if (settings.infoLogging != false) log.info "${device.displayName}: ${message}"
}

private void logDebug(String message) {
    if (settings.debugLogging) log.debug "${device.displayName}: ${message}"
}

private void logTrace(String message) {
    if (settings.traceLogging) log.trace "${device.displayName}: ${message}"
}
