/*
MIT License

Copyright (c) 2021 Martin Stenderup / mast76

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
*/
import groovy.json.JsonSlurper

def getAppName(){"Record Replay Simulator"}
def getVersion(){"1.0.0-beta"}

definition(
	name: "${getAppName()}",
	namespace: "mast76",
	author: "Martin Stenderup",
	description: "Simulates device actions",
	category: "Convenience",
    iconUrl:   "",
    iconX2Url: "",
    iconX3Url: "",
	singleInstance: true
)

preferences {
	page(name: "pageConfig")
}

def pageConfig() {
    dynamicPage(name: "", title: "", install: true, uninstall: true) {
        section("Instructions:", hideable: true, hidden: true) {
            paragraph "<b>Configuration</b>"
            paragraph "1. Select switch to trigger replay."
            paragraph "2. Select switches to record and perform replay on."
            paragraph "<b>Usage Example</b>"
            paragraph "1. Enable recording."
            paragraph "2. Perform your daily life sequence."
            paragraph "3. Stop recording."
            paragraph "4. Enable replay, use randomization for reducing predictability."
            paragraph "5. Use Rule Machine to setup at schedule to trigger replay at intervals while away"
        }
        section("<b>Configuration</b>") {
            label title: "Name", defaultValue: "${getAppName()}", required: true
            input "triggerSwitch", "capability.switch", title: "Select Trigger Switch", required: true, multiple: false
            input "actionSwitches", "capability.switch", title: "Select Action Switches", required: true, multiple: true
            input "factor", "decimal", title: "Replay Speed Factor", defaultValue: 0.0, range: "0..10", required: true, multiple: false
            input "randomization", "enum", title: "Replay Randomization (%)", options: ["0","10","20","30","40","50"], defaultValue: "0", required: true, multiple: false
            input "delayBeforeFirst", "number", title: "Delay Before First Event in Minutes", defaultValue: 0, required: true, multiple: false
        }
        section("<b>Actions</b>") {
            if(replay) {
                record = false
            } else {
                input "record", "bool", title: "Record Events", required: false, multiple: false, submitOnChange: true
            }
            if(record) {
                replay = false
            } else {
                input "replay", "bool", title: "Replay Events", required: false, multiple: false, submitOnChange: true
            }
        }
        section("<b>Logging</b>") {
            input "enableDebug", "bool", title: "Enable Debug Logging", required: false, multiple: false, submitOnChange: true
        }
         
        if(!record && ! replay) {
            section("Export / Import:") {
                paragraph "Data format is in a double array. [[millisec,deviceId,on/off],[millisec,deviceId,on/off],...]"
                input "importData", "text", title: "Recording Data", required: false, defaultValue: state.recording, multiple: false, submitOnChange: true
            }
        }
        section("<b>Info</b>") {
              paragraph "${getAppName()}" 
              paragraph "Installed version $state.version" 
        }
    }
}

mappings {
}

def installed() {
	log_debug "Installed with settings: ${settings}"
    setVersion()
	initialize()
}

def updated() {
	log_debug "Updated with settings: ${settings}"
    unsubscribe()
    triggerSwitch.off()

    if (importData) {
        log.info "Validating: $importData"
        def isValid = true
        for(row in new JsonSlurper().parseText(importData)) {
            if(!(row[0] >= 0)) {
                isValid = false
                log.error "Not a valid duration!"
            }
            if(!actionSwitches.any( { it.deviceNetworkId == row[1] } )) {
                isValid = false
                log.error "Not a valid device network id!"
            }
            if(row[2] != "on" && row[2] != "off" ) {
                isValid = false
                log.error "Not a valid action!"
            }
            if(!isValid) {
                log.error "Data: '$row' are not vaild!"
                break
            }
        }
        if(isValid) {
            log.info "Importing: $importData"
            state.recording = importData
            app.updateSetting "importData", ""
        }
    }

	initialize()
}

def initialize() {
	log_debug "Initialized with settings: ${settings}"
    state.lastEventAt = null
    if(record) {
        log.info "Recording enabled"
        state.recording = []
        subscribe(actionSwitches, "switch", deviceHandler)
    } else if (replay) {
        log.info "Replay enabled"
        subscribe(triggerSwitch, "switch", triggerHandler)
    }
}

def triggerHandler(evt) {
    log_debug "Event: ${evt.displayName} : ${evt.value}"
    if(evt.value=='on') { 
        log.info "Starting replay"
        state.replayIndex=0
        state.breakLoop = false  
        int pauseInt = delayBeforeFirst*60
        pauseInt = Math.round(pauseInt - pauseInt * (randomization as int) / 100 + Math.random() * pauseInt * (randomization as int) * 2)
        log_debug "Pausing for $pauseInt secs"
        runIn(pauseInt,replayHandler)
    } else {
        log.info "Stopping replay"
        unschedule()
        state.breakLoop = true
    }
}

def replayHandler(evt) {
    log_debug "Replay event"
    try {
        if(state.recording.size()>=state.replayIndex && !state.breakLoop) {
            def row = state.recording[state.replayIndex]
                
            actionSwitches.findAll( { it.deviceNetworkId == row[1] } ).each {
                log_debug "Setting ${row[2]}"
                if(row[2]=='on') {
                    it.on()
                } else {
                    it.off()
                }
            }
            if(state.recording.size()>=state.replayIndex++) {
                int pauseInt = Math.round(state.recording[state.replayIndex][0] * factor)
                pauseInt = Math.round(pauseInt - pauseInt * (randomization as int) / 100 + Math.random() * pauseInt * (randomization as int) * 2)
                pauseInt = pauseInt/1000
                log_debug "Pausing for $pauseInt secs"
                runIn(pauseInt,replayHandler)
            } else {
                log_debug "No more events to replay."
                triggerSwitch.off()
            }
        } else {
            log.warn "Replay was canceled!"
            triggerSwitch.off()
        }    
    } catch (Exception e) {
        log.error "Replay failed : ${e.message}"
        triggerSwitch.off()
    } 
}

def deviceHandler(evt) {
    log_debug "Recording Event: ${evt.displayName} : ${evt.value}"
    try {
        def delay
        if(state.lastEventAt) {
            delay = evt.date.getTime() - state.lastEventAt
        } else {
            delay = 0
        }
        state.lastEventAt = evt.date.getTime()
        state.recording += [[delay,evt.device.deviceNetworkId,evt.value]]
    } catch (Exception e) {
        log.error "Recording failed : ${e.message}"
    }
}

def uninstalled() {
	log_debug "In uninstalled"
}

def log_debug(msg) {
	if (enableDebug) log.debug(msg)
}
