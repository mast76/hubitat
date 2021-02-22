/*
 * SMTP Notification
 * This is a very basic SMTP client implementation. 
 * No authentication. No TLS. Requires an open relay.
 *
 */
metadata {
    definition(name: "SMTP Notification", namespace: "stenderup", author: "Martin Stenderup", importUrl: "https://raw.githubusercontent.com/mast76/hubitat/main/drivers/SMTP-Notification.groovy") {
        capability "Notification"
    }
}

preferences {
    section("Connection") {
        input "host", "text", title: "Hostname / IP", required: true
        input "port", "text", title: "Port", required: true, defaultValue: 25
        input "from", "text", title: "From", required: true
        input "to", "text", title: "To", required: true
        input "subject", "text", title: "Subject", required: true, defaultValue: "Notification" 
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

def logsOff() {
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

def sendMsg(String msg) {
     if (logEnable) log.debug "Sending msg = ${msg}"
    sendHubCommand(new hubitat.device.HubAction(msg, hubitat.device.Protocol.TELNET))
}

def deviceNotification(text) {
    if (logEnable) log.debug "Sending SMTP notification by [${settings.host}:${settings.port}]"
    try {
        state.msg = text
        state.localState="connecting"
        telnetConnect([terminalType: 'VT100'], host, port as int, null, null)
        sendMsg("HELO ${settings.host}")
    } catch (Exception e) {
        log.warn "Send failed: ${e.message}"
    }
}

def telnetStatus(String message) {
    if (logEnable) log.debug("parse ${state.localState}:${message}")
}

def parse(String message) {
    if (logEnable) log.debug("parse ${state.localState}:${message}")
    try {
        if(message.startsWith("250")) {
            if(state.localState=="connecting") {
                state.localState="sender"
                sendMsg("MAIL FROM:${settings.from}")
            } else if(state.localState=="sender") {
                state.localState="recipient"
                sendMsg("RCPT TO:${settings.to}")
            } else if(state.localState=="recipient") {
                state.localState="data"
                sendMsg("DATA")
            } else if(state.localState=="done") {
                state.localState=null
                sendMsg("QUIT")
            }
            return
        }
       
        if(message.startsWith("354")) {
            state.localState="done"
            String msg = "From: ${settings.from}\nSubject: ${settings.subject}\nTo: ${settings.to}\n\n${state.msg}\n.\n"
            sendMsg(msg)
            state.msg = null
        }
    } catch (Exception e) {
        log.warn "Send failed: ${e.message}"
    }
}
