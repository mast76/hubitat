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

def setVersion(){
    state.name = "Record Replay Simulator"
	state.version = "1.0.0"
}

definition(
	name: "Record Replay Simulator",
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
        section() {
            input "triggerSwitch", "capability.switch", title: "Select Trigger Switch", required: true, multiple: false
            input "actionSwitches", "capability.switch", title: "Select Action Switches", required: true, multiple: true
        }
        section() {
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
        section() {
                input "enableDebug", "bool", title: "Enable Debug Logging", required: false, multiple: false, submitOnChange: true
        }
    }
}

mappings {
}

def installed() {
	log_debug "Installed with settings: ${settings}"

	initialize()
}

def updated() {
	log_debug "Updated with settings: ${settings}"
    unsubscribe()
	initialize()
}

def initialize() {
	log_debug "Initialized with settings: ${settings}"
    state.lastEventAt = null
    if(record) {
        state.recording = []
        subscribe(actionSwitches, "switch", deviceHandler)
    } else if (replay) {
        subscribe(triggerSwitch, "switch", triggerHandler)
    }
}

def triggerHandler(evt) {
    log_debug "Event: ${evt.displayName} : ${evt.value}"
    try {
        if(evt.value=='on') {
            log_debug "Starting replay"
            state.breakLoop = false
            for(row in state.recording) {
                if(state.breakLoop) break
                log_debug "Pausing for ${row[0]} ms"
                pauseExecution(row[0])
                log_debug "Replay event"
                actionSwitches.findAll( { it.id == "${row[1]}"} ).each {
                    log_debug "Setting ${row[2]}"
                    if(row[2]=='on') {
                        it.on()
                    } else {
                        it.off()
                    }
                }
            } 
            evt.device.off()  
        } else {
            log_debug "Stopping replay"
            state.breakLoop = true
        }
    } catch (Exception e) {
        log.error "Replay failed : ${e.message}"
    }
}

def deviceHandler(evt) {
    log_debug "Event: ${evt.displayName} : ${evt.value}"
    try {
        def delay
        if(state.lastEventAt) {
            delay = evt.date.getTime() - state.lastEventAt
        } else {
            delay = 0
        }
        state.lastEventAt = evt.date.getTime()
        state.recording += [[delay,evt.deviceId,evt.value]]
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
