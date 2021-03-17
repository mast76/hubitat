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
def getAppName(){"Record Replay Simulator"}
def getVersion(){"1.0.0-alfa"}

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
            paragraph "<b>Usage Exsample</b>"
            paragraph "1. Enable recording."
            paragraph "2. Perform your daily life sequence."
            paragraph "3. Stop recording."
            paragraph "4. Enable replay."
            paragraph "5. Use Rule Machine to setup at schedule to trigger replay at intervals while away"
        }
        section("<b>Configuration</b>") {
            label title: "Name", defaultValue: "${getAppName()}", required: true
            input "triggerSwitch", "capability.switch", title: "Select Trigger Switch", required: true, multiple: false
            input "actionSwitches", "capability.switch", title: "Select Action Switches", required: true, multiple: true
            input "factor", "decimal", title: "Replay Speed Factor", defaultValue: 0.0, range: "0..10", required: true, multiple: false
            input "randomization", "enum", title: "Replay Randomization (%)", options: ["0","10","20","30","40","50"], defaultValue: "0", required: true, multiple: false
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
        try {
            log.info "Starting replay"
            state.breakLoop = false
            for(row in state.recording) {
                if(state.breakLoop) break
                
                int pauseInt
                pauseInt = Math.round(row[0] * factor)
                pauseInt = Math.round(pauseInt - pauseInt * (randomization as int) / 100 + Math.random() * pauseInt * (randomization as int) * 2)
                log_debug "Pausing for $pauseInt ms"
                pauseExecution(pauseInt)
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
        } catch (Exception e) {
            log.error "Replay failed : ${e.message}"
        } finally {
            evt.device.off()
        }   
    } else {
           log_debug "Stopping replay"
        state.breakLoop = true
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
