/**
 *  Ikea TRÅDFRI Shortcut Button
 *
 *  Original Smartthings implementation:
 *  Copyright 2015, 2021 Mitch Pond / iquix
 *
 *  Hubitat port:
 *  Copyright 2021, Martin Stenderup / mast76
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */

import groovy.json.JsonOutput
import hubitat.zigbee.zcl.DataType

metadata {
    definition (name: "Ikea TRADFRI Shortcut Button", namespace: "mast76", author: "Martin Stenderup", importUrl: "https://raw.githubusercontent.com/mast76/hubitat/main/drivers/ikea-tradfri-shortcut-button.groovy") {
        capability "Actuator"
        capability "Battery"
        capability "PushableButton"
        capability "HoldableButton"        
        capability "Configuration"
        capability "Refresh"
        capability "Sensor"
        capability "HealthCheck"

        fingerprint model: "TRADFRI SHORTCUT Button", manufacturer:"IKEA of Sweden", profileId:"0104", inClusters:"0000,0001,0003,0009,0020,1000", outClusters:"0003,0004,0006,0008,0019,0102,1000", application:"21" 
    }

    simulator {}

    preferences {
	    section {
			input(name: "debugLogging", type: "bool", title: "Enable debug logging", description: "", defaultValue: false, submitOnChange: true, displayDuringSetup: false, required: false)
			input(name: "traceLogging", type: "bool", title: "Enable trace logging", description: "", defaultValue: false, submitOnChange: true, displayDuringSetup: false, required:false)			
		}
        section {
            input ("holdTime", "number", title: "Minimum time in seconds for a press to count as \"held\"", defaultValue: 0.5, displayDuringSetup: false)
        }
    }

    tiles {
        standardTile("button", "device.button", width: 2, height: 2) {
            state "default", label: "", icon: "st.unknown.zwave.remote-controller", backgroundColor: "#ffffff"
            state "button 1 pushed", label: "pushed #1", icon: "st.unknown.zwave.remote-controller", backgroundColor: "#00A0DC"
        }

        valueTile("battery", "device.battery", decoration: "flat", inactiveLabel: false) {
            state "battery", label:'${currentValue}% battery', unit:""
        }

        standardTile("refresh", "device.refresh", inactiveLabel: false, decoration: "flat") {
            state "default", action:"refresh.refresh", icon:"st.secondary.refresh"
        }
        main (["button"])
        details(["button", "battery", "refresh"])
    }
}

def logDebug(String msg) {
	if(debugLogging) {
		log.debug msg
	}
}

def logTrace(String msg) {
	if(traceLogging){
		logTrace msg
	}
}

def parse(String description) {
    logTrace "parse"
    logDebug "description is $description"
    def event = zigbee.getEvent(description)
    if (event) {
        sendEvent(event)
    }
    else {
        if ((description?.startsWith("catchall:")) || (description?.startsWith("read attr -"))) {
            def descMap = zigbee.parseDescriptionAsMap(description)
            if (descMap.clusterInt == 0x0001 && descMap.attrInt == 0x0020 && descMap.value != null) {
                event = getBatteryResult(zigbee.convertHexToInt(descMap.value))
            }
            else if (descMap.clusterInt == 0x0006 || descMap.clusterInt == 0x0008) {
                event = parseNonIasButtonMessage(descMap)
            }
        }
        else if (description?.startsWith('zone status')) {
            event = parseIasButtonMessage(description)
        }

        logDebug "Parse returned $event"
        def result = event ? createEvent(event) : []

        if (description?.startsWith('enroll request')) {
            List cmds = zigbee.enrollResponse()
            result = cmds?.collect { new hubitat.device.HubAction(it) }
        }
        return result
    }
}

private Map parseIasButtonMessage(String description) {
    logTrace "parseIasButtonMessage"
    logDebug "description is $description"
    def zs = zigbee.parseZoneStatus(description)
    return zs.isAlarm2Set() ? getButtonResult("press") : getButtonResult("release")
}

private Map getBatteryResult(rawValue) {
    logDebug 'Battery'
    def volts = rawValue / 10
    if (volts > 3.0 || volts == 0 || rawValue == 0xFF) {
        logDebug 'Battery n/a'
        return [:]
    }
    else {
        def result = [
                name: 'battery'
        ]
        def minVolts = 2.1
        def maxVolts = 3.0
        def pct = (volts - minVolts) / (maxVolts - minVolts)
        result.value = Math.min(100, (int)(pct * 100))
        def linkText = getLinkText(device)
        result.descriptionText = "${linkText} battery was ${result.value}%"
        logDebug 'Battery "${result.descriptionText}"'
        return result
    }
}

private Map parseNonIasButtonMessage(Map descMap){
    logDebug "parseNonIasButtonMessage"
    def buttonState = ""
    def buttonNumber = 0
    if ((device.getDataValue("model") == "3460-L") &&(descMap.clusterInt == 0x0006)) {
        if (descMap.commandInt == 1) {
            getButtonResult("press")
        }
        else if (descMap.commandInt == 0) {
            getButtonResult("release")
        }
    }
    else if ((device.getDataValue("model") == "3450-L") && (descMap.clusterInt == 0x0006)) {
        if (descMap.commandInt == 1) {
            getButtonResult("press")
        }
        else if (descMap.commandInt == 0) {
            def button = 1
            switch(descMap.sourceEndpoint) {
                case "01":
                    button = 4
                    break
                case "02":
                    button = 3
                    break
                case "03":
                    button = 1
                    break
                case "04":
                    button = 2
                    break
            }
        
            getButtonResult("release", button)
        }
    }
    else if (descMap.clusterInt == 0x0006) {
        buttonState = "pushed"
        if (descMap.command == "01") {
            buttonNumber = 1
        }
        else if (descMap.command == "00") {
            buttonNumber = 2
        }
        if (buttonNumber !=0) {
            def descriptionText = "$device.displayName button $buttonNumber was $buttonState"
            return createEvent(name: buttonState, value: buttonNumber, descriptionText: descriptionText, isStateChange: true)
        }
        else {
            return [:]
        }
    }
    else if (descMap.clusterInt == 0x0008) {
        if (descMap.command == "05") {
            state.buttonNumber = 1
            getButtonResult("press", 1)
        }
        else if (descMap.command == "01") {
            state.buttonNumber = 2
            getButtonResult("press", 2)
        }
        else if (descMap.command == "03" || descMap.command == "07") {
            getButtonResult("release", state.buttonNumber)
        }
    }
}

def refresh() {
    logDebug "Refreshing Battery"

    return zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x20) +
            zigbee.enrollResponse()
}

def configure() {
    logDebug "Configuring Reporting, IAS CIE, and Bindings."
    List<String> cmds = []
    cmds.addAll(zigbee.onOffConfig())
    cmds.addAll(zigbee.levelConfig())
    cmds.addAll(zigbee.configureReporting(zigbee.POWER_CONFIGURATION_CLUSTER, 0x20, DataType.UINT8, 30, 21600, 0x01))
    cmds.addAll(zigbee.enrollResponse())
    cmds.addAll(zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x20))
    logDebug (device.getDataValue("model"))
    cmds.addAll(
        "zdo bind 0x${device.deviceNetworkId} 1 1 6 {${device.zigbeeId}} {}", "delay 300",
        "zdo bind 0x${device.deviceNetworkId} 2 1 6 {${device.zigbeeId}} {}", "delay 300",
        "zdo bind 0x${device.deviceNetworkId} 3 1 6 {${device.zigbeeId}} {}", "delay 300",
        "zdo bind 0x${device.deviceNetworkId} 4 1 6 {${device.zigbeeId}} {}", "delay 300"
    )
    return cmds

}

private Map getButtonResult(buttonState, buttonNumber = 1) {
    logTrace "getButtonResult"
    logDebug "buttonState = $bubuttonState, buttonNumber = buttonNumber"
    if (buttonState == 'release') {
        logDebug "Button was value : $buttonState"
        if(state.pressTime == null) {
            return [:]
        }
        def timeDiff = now() - state.pressTime
        log.info "timeDiff: $timeDiff"
        def holdPreference = holdTime ?: 0.5
        log.info "holdp1 : $holdPreference"
        holdPreference = (holdPreference as int) * 1000
        log.info "holdp2 : $holdPreference"
        if (timeDiff > 10000) {         //timeDiff>10sec check for refresh sending release value causing actions to be executed
            return [:]
        }
        else {
            if (timeDiff < holdPreference) {
                buttonState = "pushed"
            }
            else {
                buttonState = "held"
            }
            def descriptionText = "$device.displayName button $buttonNumber was $buttonState"
            return createEvent(name: buttonState, value: buttonNumber, descriptionText: descriptionText, isStateChange: true)
        }
    }
    else if (buttonState == 'press') {
        logDebug "Button was value : $buttonState"
        state.pressTime = now()
        log.info "presstime: ${state.pressTime}"
        return [:]
    }
}

def installed() {
    initialize()

    // Initialize default states
    device.currentValue("numberOfButtons")?.times {
        sendEvent(name: "pushed", value: it+1, displayed: false)
    }
}

def updated() {
    initialize()
}
def initialize() {
    // Arrival sensors only goes OFFLINE when Hub is off
    sendEvent(name: "DeviceWatch-Enroll", value: JsonOutput.toJson([protocol: "zigbee", scheme:"untracked"]), displayed: false)
    sendEvent(name: "numberOfButtons", value: 1, displayed: false)
}

def ping() {
    logDebug 'Pinging'
}
