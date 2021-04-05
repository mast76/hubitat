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
    definition (name: 'Ikea TRADFRI Shortcut Button', namespace: 'mast76', author: 'Martin Stenderup', importUrl: 'https://raw.githubusercontent.com/mast76/hubitat/main/drivers/ikea-tradfri-shortcut-button.groovy') {
        capability 'Actuator'
        capability 'Battery'
        capability 'PushableButton'
        capability 'HoldableButton'
        capability 'Configuration'
        capability 'Refresh'
        capability 'Sensor'
        capability 'HealthCheck'
        capability 'Momentary'
        command 'push'
        command 'hold'

        fingerprint model: 'TRADFRI SHORTCUT Button', manufacturer:'IKEA of Sweden', profileId:'0104', inClusters:'0000,0001,0003,0009,0020,1000', outClusters:'0003,0004,0006,0008,0019,0102,1000', application:'21'
    }

    preferences {
        section {
            input(name: 'debugLogging', type: 'bool', title: 'Enable debug logging', description: '', defaultValue: false, submitOnChange: true, displayDuringSetup: false, required: false)
            input(name: 'traceLogging', type: 'bool', title: 'Enable trace logging', description: '', defaultValue: false, submitOnChange: true, displayDuringSetup: false, required:false)
        }
        section {
            input ('holdTime', 'decimal', title: "Minimum time in seconds for a press to count as \"held\"", defaultValue: 0.5, displayDuringSetup: false)
        }
    }
}

def logDebug(String msg) {
    if (debugLogging) {
        log.debug msg
    }
}

def logTrace(String msg) {
    if (traceLogging) {
        log.trace msg
    }
}

def parse(String description) {
    logTrace 'parse'
    logDebug "description is $description"
    def event = zigbee.getEvent(description)
    if (event) {
        logTrace 'Could use getEvent for parsing.'
        sendEvent(event)
    }
    else {
        logTrace 'Could not use getEvent for parsing, trying custom parsing.'
        if ((description?.startsWith('catchall:')) || (description?.startsWith('read attr -'))) {
            logTrace 'Got type of event.'
            def descMap = zigbee.parseDescriptionAsMap(description)
            if (descMap.clusterInt == 0x0001 && descMap.attrInt == 0x0020 && descMap.value != null) {
                logTrace 'Matched cluster to battery event.'
                event = getBatteryResult(zigbee.convertHexToInt(descMap.value))
            }
            else if (descMap.clusterInt == 0x0006) {
                logTrace 'Matched cluster to short press event.'
                event = parseShortPress(descMap)
            }
            else if (descMap.clusterInt == 0x0008) {
                logTrace 'Matched cluster to level / long press event.'
                event = parseLongPress(descMap)
            }
            else if (descMap.clusterInt == 0x8021) {
                logDebug 'Matched bind responce'
            }
            else if (descMap.clusterInt == 0x8022) {
                logDebug 'Matched unbind responce'
            }
            else if (descMap.clusterInt == 0x0500) {
                logDebug 'Matched IAS Zone'
            }
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

private Map parseLongPress(descMap) {
    logTrace 'parseLongPress'
    logDebug JsonOutput.toJson(descMap)
    if (descMap.command == '05') {
        getButtonResult('press')
    }
    else if (descMap.command == '07') {
        getButtonResult('release')
    } else {
        return [:]
    }
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

private Map parseShortPress(Map descMap) {
    logDebug 'parseShortPress'
    def buttonState = ''
    def buttonNumber = 0
    if (descMap.clusterInt == 0x0006) {
        buttonState = 'pushed'
        if (descMap.command == '01') {
            buttonNumber = 1
        }
        else if (descMap.command == '00') {
            buttonNumber = 2
        }
        if (buttonNumber != 0) {
            def descriptionText = "$device.displayName button $buttonNumber was $buttonState"
            return createEvent(name: buttonState, value: buttonNumber, descriptionText: descriptionText, isStateChange: true)
        }
        else {
            return [:]
        }
    }
}

def refresh() {
    logDebug 'Refreshing Battery'

    return zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x20) +
            zigbee.enrollResponse()
}

def configure() {
    logDebug 'Configuring Reporting, IAS CIE, and Bindings.'
    List<String> cmds = []
    cmds.addAll(zigbee.onOffConfig())
    cmds.addAll(zigbee.levelConfig())
    cmds.addAll(zigbee.configureReporting(zigbee.POWER_CONFIGURATION_CLUSTER, 0x20, DataType.UINT8, 30, 21600, 0x01))
    cmds.addAll(zigbee.enrollResponse())
    cmds.addAll(zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x20))
    logDebug (device.getDataValue('model'))
    cmds.addAll(
        "zdo bind 0x${device.deviceNetworkId} 1 1 6 {${device.zigbeeId}} {}", 'delay 300',
        "zdo bind 0x${device.deviceNetworkId} 2 1 6 {${device.zigbeeId}} {}", 'delay 300',
        "zdo bind 0x${device.deviceNetworkId} 3 1 6 {${device.zigbeeId}} {}", 'delay 300',
        "zdo bind 0x${device.deviceNetworkId} 4 1 6 {${device.zigbeeId}} {}", 'delay 300',
        "zdo bind 0x${device.deviceNetworkId} 1 1 8 {${device.zigbeeId}} {}", 'delay 300',
        "zdo bind 0x${device.deviceNetworkId} 2 1 8 {${device.zigbeeId}} {}", 'delay 300',
        "zdo bind 0x${device.deviceNetworkId} 3 1 8 {${device.zigbeeId}} {}", 'delay 300',
        "zdo bind 0x${device.deviceNetworkId} 4 1 8 {${device.zigbeeId}} {}", 'delay 300'
    )
    return cmds
}

private Map getButtonResult(buttonState, buttonNumber = 1) {
    logTrace 'getButtonResult'
    logDebug "buttonState = $buttonState, buttonNumber = buttonNumber"
    if (buttonState == 'release') {
        logDebug "Button was value : $buttonState"
        if (state.pressTime == null) {
            return [:]
        }
        def timeDiff = now() - state.pressTime
        log.info "timeDiff: $timeDiff"
        def holdPreference = (holdTime as double) ?: 0.5
        log.info "holdp1 : $holdPreference"
        holdPreference = Math.round(holdPreference  * 1000)
        log.info "holdp2 : $holdPreference"
        if (timeDiff > 10000) {         //timeDiff>10sec check for refresh sending release value causing actions to be executed
            return [:]
        }
        else {
            if (timeDiff < holdPreference) {
                buttonState = 'pushed'
            }
            else {
                buttonState = 'held'
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
    device.currentValue('numberOfButtons')?.times {
        sendEvent(name: 'pushed', value: it + 1, displayed: false)
    }
}

def updated() {
    initialize()
}
def initialize() {
    // Arrival sensors only goes OFFLINE when Hub is off
    sendEvent(name: 'DeviceWatch-Enroll', value: JsonOutput.toJson([protocol: 'zigbee', scheme:'untracked']), displayed: false)
    sendEvent(name: 'numberOfButtons', value: 1, displayed: false)
}

def push(buttonNumber = 1) {
    if (buttonNumber && buttonNumber > 1) {
        log.warn("No such button '$buttonNumber'")
        return
    }
    def descriptionText = "$device.displayName button $buttonNumber was pushed"
    sendEvent(name: 'pushed', value: buttonNumber, descriptionText: descriptionText, isStateChange: true)
}

def hold(buttonNumber = 1) {
    if (buttonNumber && buttonNumber > 1) {
        log.warn("No such button '$buttonNumber'")
        return
    }
    def descriptionText = "$device.displayName button $buttonNumber was held"
    sendEvent(name: 'held', value: buttonNumber, descriptionText: descriptionText, isStateChange: true)
}

def ping() {
    logDebug 'Pinging'
}
