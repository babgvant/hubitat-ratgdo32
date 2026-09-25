/** Guided setup for the ratgdo32 Direct HTTP Garage Door driver. */
definition(
    name: "ratgdo32 Garage Door",
    namespace: "babgvant",
    author: "Andrew Van Til",
    description: "Create and configure a local ratgdo32 garage-door device.",
    category: "Convenience",
    documentationLink: "https://github.com/babgvant/hubitat-ratgdo32/blob/main/README.md",
    importUrl: "https://raw.githubusercontent.com/babgvant/hubitat-ratgdo32/main/ratgdo32-app.groovy",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: false
)

preferences {
    page(name: "setupPage")
}

Map setupPage() {
    dynamicPage(name: "setupPage", title: "ratgdo32 Garage Door", install: true, uninstall: true) {
        section("Connect to your ratgdo32") {
            paragraph "Use the local address of a ratgdo32 running homekit-ratgdo32 firmware. Give it a DHCP reservation so the address stays the same."
            input name: "controllerName", type: "text", title: "Garage door name", required: true, defaultValue: "Garage Door"
            input name: "ipAddress", type: "text", title: "ratgdo32 IP address or hostname", required: true,
                    description: "Address only, without http:// or a path"
            input name: "httpPort", type: "number", title: "HTTP port", required: true, defaultValue: 80, range: "1..65535"
        }
        section("Authentication") {
            paragraph "If Require Password is enabled in the ratgdo32 firmware, enter its HTTP credentials here."
            input name: "httpUsername", type: "text", title: "HTTP username", required: false, defaultValue: "admin"
            input name: "httpPassword", type: "password", title: "HTTP password", required: false
        }
        section("Device") {
            def child = getChildDevice(deviceNetworkId())
            if (child) {
                paragraph "Device: ${child.displayName}. Save changes here to update its connection settings."
            } else {
                paragraph "Select Done to create the garage door device. It will connect automatically."
            }
            if (state.setupError) paragraph "Setup error: ${state.setupError}"
        }
    }
}

void installed() { syncDevice() }
void updated() { syncDevice() }

private void syncDevice() {
    state.remove("setupError")
    String host = settings.ipAddress?.toString()?.trim()
    if (!host || host.contains("://") || host.contains("/") || host.contains("?") || host.contains("#")) {
        state.setupError = "Enter an IP address or hostname without a URL scheme or path."
        log.error "${app.label}: ${state.setupError}"
        return
    }

    String dni = deviceNetworkId()
    def child = getChildDevice(dni)
    if (!child) {
        try {
            child = addChildDevice("babgvant", "ratgdo32 Direct HTTP Garage Door", dni,
                    [label: settings.controllerName?.toString()?.trim() ?: "Garage Door", isComponent: false])
        } catch (Exception e) {
            state.setupError = "Could not create the device. Check that the ratgdo32 driver was installed with this app."
            log.error "${app.label}: ${state.setupError} ${e.message}"
            return
        }
    }

    String label = settings.controllerName?.toString()?.trim() ?: "Garage Door"
    if (child.label != label) child.setLabel(label)
    child.updateSetting("ipAddress", [value: host, type: "text"])
    child.updateSetting("httpPort", [value: (settings.httpPort ?: 80).toString(), type: "number"])
    child.updateSetting("httpUsername", [value: settings.httpUsername?.toString() ?: "", type: "text"])
    child.updateSetting("httpPassword", [value: settings.httpPassword?.toString() ?: "", type: "password"])
    child.initialize()
}

private String deviceNetworkId() { "ratgdo32-${app.id}" }
